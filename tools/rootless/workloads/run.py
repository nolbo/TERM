#!/usr/bin/env python3
"""Prepare and compare verified Rootless work, without network downloads."""
import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import statistics
import sys
import time

from common import (SCHEMA, CheckFailure, check_exit, child_environment, distribution,
                    execute, file_digest, parse_counters, require, validate_agent_config, write_json)
from fixtures import prepare


HERE = Path(__file__).resolve().parent
DEFAULT_CASES = ("dpkg-1000", "dpkg-10000", "apt-1000", "apt-10000",
                 "offline-packages",
                 "repository:synthetic-10000", "repository:synthetic-100000", "repository:pinned",
                 "python-verify:synthetic-10000", "local-replay:synthetic-10000",
                 "two-workspaces:synthetic-10000")


def expand(value, substitutions):
    for key, replacement in substitutions.items():
        value = value.replace("{" + key + "}", str(replacement))
    return value


def profile_identity(profile):
    artifacts = {}
    for name, value in profile.get("artifacts", {}).items():
        path = Path(value)
        require(path.is_file(), f"profile artifact is missing: {name}")
        artifacts[name] = {"sha256": file_digest(path), "bytes": path.stat().st_size}
    # Credentials are neither inspected nor copied into result/reproduction
    # files. The original operator-owned profile remains the reproducible input.
    visible = {key: value for key, value in profile.get("env", {}).items()
               if key.startswith("PROOT_") and not any(secret in key.upper() for secret in ("TOKEN", "KEY", "PASSWORD", "SECRET"))}
    return {"kind": profile.get("kind", "custom"), "artifacts": artifacts,
            "environment_keys": sorted(profile.get("env", {})), "runtime_options": visible,
            "storage_format": profile.get("storage_format", "unrecorded"),
            "description": profile.get("description", "")}


def validate_profile(profile):
    require(isinstance(profile, dict), "profile must be a JSON object")
    argv = profile.get("command")
    require(isinstance(argv, list) and argv and all(isinstance(value, str) for value in argv),
            "profile command must be a nonempty argv array; shell strings are not accepted")
    require(isinstance(profile.get("env", {}), dict) and
            all(isinstance(key, str) and isinstance(value, str) for key, value in profile.get("env", {}).items()),
            "profile env must map strings to strings")
    if profile.get("kind") == "rootless":
        require("engine" in profile.get("artifacts", {}) and "loader" in profile.get("artifacts", {}),
                "rootless comparison must pin engine and loader artifacts (and dso when enabled)")


def one_run(profile, bundle, directory, case, diagnostic=False, agent=None, enable_agent=False, keep_workspace=False):
    if agent is not None:
        validate_agent_config(agent)
    directory.mkdir(parents=True)
    work = directory / "workspace"
    work.mkdir()
    guest_bundle = profile.get("guest_bundle", str(bundle))
    guest_workspace = profile.get("guest_workspace", str(work))
    substitutions = {"host_bundle": str(bundle), "host_workspace": str(work),
                     "guest_bundle": guest_bundle, "guest_workspace": guest_workspace,
                     "python": sys.executable}
    argv = [expand(value, substitutions) for value in profile["command"]]
    env = child_environment()
    env.update({key: expand(value, substitutions) for key, value in profile.get("env", {}).items()})
    if diagnostic:
        env["PROOT_PERF"] = "1"
        env["PROOT_FASTSTAT_REPORT"] = "1"
    else:
        require(env.get("PROOT_PERF", "0") in ("", "0") and env.get("PROOT_FASTSTAT_REPORT", "0") in ("", "0"),
                "diagnostic counters are enabled in a timing profile; use --diagnostic separately")
    name, _, repository = case.partition(":")
    job = {"schema": SCHEMA, "case": name, "bundle": guest_bundle, "work": guest_workspace,
           "diagnostic": diagnostic, "repository": repository or "synthetic-10000",
           "enable_authenticated_run": enable_agent}
    if agent:
        job["agent"] = agent
    write_json(work / "job.json", job)
    argv += ["--job", str(Path(guest_workspace) / "job.json")]
    process = execute(argv, directory, env=env, timeout=profile.get("timeout_seconds", 1200),
                      stdout_path=directory / "worker.stdout", stderr_path=directory / "worker.stderr")
    reports = []
    for line in (directory / "worker.stdout").read_text(errors="replace").splitlines():
        try:
            parsed = json.loads(line)
        except ValueError:
            continue
        if isinstance(parsed, dict) and "rootless_workload" in parsed:
            reports.append(parsed["rootless_workload"])
    if len(reports) != 1:
        result = {"schema": SCHEMA, "case": name, "status": "failed",
                  "reason": "worker did not emit exactly one structured result; tracer exit 0 is insufficient"}
    else:
        result = reports[0]
        if process["timed_out"] or process["exit_code"] != (1 if result["status"] == "failed" else 0):
            result.update(status="failed", reason="launcher/guest exit disagrees with worker result or timed out")
    result["launcher"] = process
    result["outer_counters"] = parse_counters((directory / "worker.stderr").read_text(errors="replace"))
    result["artifact_directory"] = str(directory)
    cleanup_start = time.monotonic_ns()
    if not keep_workspace and not enable_agent:
        for relative in ("repository", "session-0/repository", "session-1/repository", "target", "apt", "home",
                         "session-0/home", "session-1/home"):
            path = work / relative
            if path.is_dir() and not path.is_symlink():
                shutil.rmtree(path)
    result["cleanup_ms"] = (time.monotonic_ns() - cleanup_start) / 1e6
    result["workspace_retained"] = keep_workspace or enable_agent
    write_json(directory / "result.json", result)
    return result


def resource_distribution(phases, key):
    values = [phase[key] for phase in phases if phase.get(key) is not None]
    unit = "ms" if key.endswith("_ms") else "KiB" if key == "peak_rss_kib" else "count"
    result = {"unit": unit, "count": len(values),
              "unavailable_samples": sum(phase.get(key) is None for phase in phases)}
    stats = distribution(values)
    if stats is not None:
        result.update({name.removesuffix("_ms"): value for name, value in stats.items()})
    return result


def summarize(rows, pairs, eligible):
    cases = sorted({row["case_key"] for row in rows})
    summaries = {}
    for case in cases:
        chosen = [row for row in rows if row["case_key"] == case]
        passed = [row for row in chosen if row["result"]["status"] == "passed"]
        by_mode = {mode: [row for row in passed if row["mode"] == mode] for mode in ("baseline", "candidate")}
        statuses = {mode: {status: sum(row["mode"] == mode and row["result"]["status"] == status for row in chosen)
                            for status in ("passed", "failed", "skipped")} for mode in by_mode}
        complete = all(len(values) == pairs for values in by_mode.values())
        item = {"status_counts": statuses, "comparison_eligible": eligible and complete,
                "measured_ms": {mode: distribution([row["result"]["measured_ms"] for row in values])
                                for mode, values in by_mode.items()},
                "workflow_elapsed_ms": {mode: distribution([row["result"]["workflow_elapsed_ms"] for row in values])
                                        for mode, values in by_mode.items()},
                "first_stdout_ms": {mode: distribution([row["result"]["first_measured_stdout_ms"] for row in values
                                                         if row["result"].get("first_measured_stdout_ms") is not None])
                                    for mode, values in by_mode.items()}}
        # Failed/skipped attempts never disappear into a successful median or
        # become a zero-ms speedup. Paired savings require every declared pair.
        if complete:
            matched = {mode: {row["pair"]: row["result"]["measured_ms"] for row in values}
                       for mode, values in by_mode.items()}
            savings = [matched["baseline"][index] - matched["candidate"][index] for index in range(pairs)]
            baseline = item["measured_ms"]["baseline"]["median_ms"]
            candidate = item["measured_ms"]["candidate"]["median_ms"]
            item.update(paired_absolute_savings_ms=distribution(savings),
                        median_absolute_savings_ms=baseline - candidate,
                        median_time_reduction_percent=100 * (baseline - candidate) / baseline if baseline else None)
        else:
            item["not_comparable_reason"] = "at least one declared attempt failed or was skipped"
        phase_names = sorted({phase["name"] for row in passed for phase in row["result"]["phases"] if phase["measured"]})
        item["phases"] = {name: {mode: distribution([phase["wall_ms"] for row in values
                                for phase in row["result"]["phases"] if phase["measured"] and phase["name"] == name])
                                for mode, values in by_mode.items()} for name in phase_names}
        metrics = ("user_cpu_ms", "system_cpu_ms", "peak_rss_kib",
                   "voluntary_context_switches", "involuntary_context_switches")
        resources = {}
        for name in phase_names:
            samples = {mode: [phase for row in values for phase in row["result"]["phases"]
                             if phase["measured"] and phase["name"] == name]
                       for mode, values in by_mode.items()}
            available = [key for key in metrics if any(phase.get(key) is not None
                        for phases in samples.values() for phase in phases)]
            if available:
                resources[name] = {key: {mode: resource_distribution(phases, key)
                                  for mode, phases in samples.items()} for key in available}
        item["phase_resources"] = resources
        item["resource_scope"] = "per-command wait4; guest/tracer unseparated; no workflow CPU or summed RSS inferred"
        summaries[case] = item
    return summaries


def compare(args):
    bundle = Path(args.bundle).resolve()
    manifest_path = bundle / "manifest.json"
    require(manifest_path.is_file(), "prepare a bundle first")
    profiles = {mode: json.loads(Path(getattr(args, mode)).read_text()) for mode in ("baseline", "candidate")}
    for profile in profiles.values():
        validate_profile(profile)
    require(args.pairs >= 10 or args.smoke, "timing comparisons require at least 10 pairs; --smoke marks smaller correctness runs")
    require(1 <= args.pairs <= 1000, "pairs must be in 1..1000")
    output = Path(args.output).resolve()
    require(not output.exists(), "output directory must not already exist")
    output.mkdir(parents=True)
    identities = {mode: profile_identity(profile) for mode, profile in profiles.items()}
    kinds = [profile.get("kind") for profile in profiles.values()]
    eligible = not args.smoke and not args.diagnostic and all(kind == "rootless" for kind in kinds)
    agent = json.loads(Path(args.agent_config).read_text()) if args.agent_config else None
    if agent is not None:
        validate_agent_config(agent)
    cases = args.case or list(DEFAULT_CASES)
    result = {"schema": SCHEMA, "status": "running", "pairs": args.pairs,
              "scope": "diagnostic" if args.diagnostic else "correctness-smoke" if args.smoke else
                       "Rootless baseline/candidate timing" if eligible else "local/control harness validation; not Rootless speed evidence",
              "bundle_manifest_sha256": file_digest(manifest_path), "profiles": identities,
              "case_order": cases, "rows": [], "support_matrix": {},
              "authenticated_agent_run_enabled": args.enable_authenticated_run,
              "reproduce_argv": [sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]],
              "cpu_note": "launcher/worker CPU can include tracer and descendants; guest/tracer components unseparated",
              "cache_note": "fixture extraction and precondition checks warm cache; first run separately labeled, never cold"}
    output.joinpath("reproduce.txt").write_text(shlex.join(result["reproduce_argv"]) + "\n")
    write_json(output / "results.json", result)
    # Version/help and real authenticated tasks are separate gates, not counted
    # as successful tool replay or as missing zero-duration timing samples.
    if not args.no_cli_probe:
        for mode, profile in profiles.items():
            result["support_matrix"][mode] = one_run(profile, bundle, output / f"support-{mode}", "cli-probe", args.diagnostic)
    if not args.enable_authenticated_run:
        result["actual_agent"] = {"status": "skipped", "reason": "no explicit authenticated-run opt-in; no authentication was inspected/requested"}
    elif agent is None:
        raise CheckFailure("--enable-authenticated-run requires --agent-config with a fixed task and output verification")
    else:
        result["actual_agent"] = {mode: one_run(profile, bundle, output / f"actual-agent-{mode}",
            "agent-run:" + args.agent_repository, args.diagnostic, agent, True) for mode, profile in profiles.items()}
    for pair in range(args.pairs):
        order = ("baseline", "candidate") if pair % 2 == 0 else ("candidate", "baseline")
        for index, case in enumerate(cases):
            for mode in order:
                directory = output / f"pair-{pair + 1:03}-case-{index:02}-{mode}"
                value = one_run(profiles[mode], bundle, directory, case, args.diagnostic, keep_workspace=args.keep_workspaces)
                result["rows"].append({"pair": pair, "mode": mode, "case_key": case,
                                       "first_invocation_for_case": pair == 0, "result": value})
                result["summaries"] = summarize(result["rows"], args.pairs, eligible)
                write_json(output / "results.json", result)
                print(json.dumps({"pair": pair + 1, "mode": mode, "case": case, "status": value["status"],
                                  "measured_ms": value.get("measured_ms"), "reason": value.get("reason")}), flush=True)
    failed = any(row["result"]["status"] == "failed" for row in result["rows"])
    failed |= any(value["status"] == "failed" for value in result["support_matrix"].values())
    if args.enable_authenticated_run:
        failed |= any(value["status"] == "failed" for value in result["actual_agent"].values())
    skipped = any(row["result"]["status"] == "skipped" for row in result["rows"])
    result["status"] = "failed" if failed else "completed-with-skips" if skipped else "completed"
    result["summaries"] = summarize(result["rows"], args.pairs, eligible)
    write_json(output / "results.json", result)
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    fixture = subparsers.add_parser("prepare", help="create pinned, offline fixtures from explicit local inputs")
    fixture.add_argument("--output", required=True)
    fixture.add_argument("--repository-count", type=int, action="append")
    fixture.add_argument("--package-count", type=int, action="append")
    fixture.add_argument("--repository", help="explicit repository input; only its committed archive is read")
    fixture.add_argument("--revision", help="required full commit hash with --repository")
    fixture.add_argument("--repository-path", action="append", default=[], help="optional committed subtree; recorded as partial repository")
    fixture.add_argument("--offline-deb", action="append", default=[])
    run = subparsers.add_parser("run", help="alternate baseline/candidate argv profiles, checking every output")
    run.add_argument("--bundle", required=True)
    run.add_argument("--baseline", required=True)
    run.add_argument("--candidate", required=True)
    run.add_argument("--output", required=True)
    run.add_argument("--pairs", type=int, default=10)
    run.add_argument("--case", action="append")
    run.add_argument("--smoke", action="store_true", help="explicit correctness-only scope; permits fewer than 10 pairs")
    run.add_argument("--diagnostic", action="store_true", help="separate counter run, never eligible for timing adoption")
    run.add_argument("--no-cli-probe", action="store_true")
    run.add_argument("--keep-workspaces", action="store_true", help="retain extracted trees/package DBs; large runs otherwise preserve logs/results only")
    run.add_argument("--agent-config")
    run.add_argument("--agent-repository", default="synthetic-10000")
    run.add_argument("--enable-authenticated-run", action="store_true", help="operator has explicitly provisioned/authenticated the real CLI task")
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            manifest = prepare(args.output, counts=args.repository_count or (10000, 100000),
                package_counts=args.package_count or (1000, 10000), repository=args.repository,
                revision=args.revision, repository_paths=args.repository_path, offline_debs=args.offline_deb)
            print(json.dumps({"bundle": str(Path(args.output).resolve()), "repositories": list(manifest["repositories"]),
                              "package_counts": list(manifest["packages"])}))
            return 0
        return compare(args)
    except (CheckFailure, OSError, ValueError) as error:
        print("workload harness: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
