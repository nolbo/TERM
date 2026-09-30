#!/usr/bin/env python3
"""Validate pulled device-batch artifacts without publishing private device paths."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys

from common import SCHEMA, CheckFailure, require, write_json
from run import summarize


TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}\Z")
ARTIFACTS = ("libproot-baseline.so", "libfaststat-baseline.so", "libproot.so",
             "libproot-loader.so", "libproot-faststat.so", "manifest.json")
FATAL = re.compile(r"SIG(?:SEGV|ILL|ABRT|BUS|FPE)\b|segmentation fault|"
                   r"(?:terminated with|uncaught target) signal (?:4|6|7|8|11)\b", re.I)
TIMEOUT = re.compile(r"timed out|watchdog.*timeout|killed after.*timeout", re.I)
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
RESOURCE_METRICS = ("user_cpu_ms", "system_cpu_ms", "peak_rss_kib",
                    "voluntary_context_switches", "involuntary_context_switches")
VERSION = r"[0-9]{1,6}(?:\.[0-9]{1,6}){0,3}(?:(?:a|b|rc)[0-9]{1,5}|-(?:alpha|beta|rc|dev)(?:[.-][0-9]{1,5})?)?"
ARCH_VERSION = r"(?:arm64|aarch64|armhf|amd64|x86_64|i386|riscv64|all)"
TOOL_VERSIONS = {
    "git": rf"git version (?P<version>{VERSION})",
    "rg": rf"ripgrep (?P<version>{VERSION})(?: \(rev [0-9a-f]{{7,40}}\))?",
    "dpkg": rf"Debian 'dpkg' package management program version (?P<version>{VERSION})(?: \({ARCH_VERSION}\))?\.",
    "apt-get": rf"apt (?P<version>{VERSION})(?: \({ARCH_VERSION}\))?",
    "python3": rf"Python (?P<version>{VERSION})",
    "codex": rf"(?:codex-cli|codex) (?P<version>{VERSION})",
    "claude": rf"(?P<version>{VERSION})(?: \(Claude Code\))?",
}


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def executable_metadata(value):
    if not isinstance(value, dict):
        return {}
    result = {}
    if isinstance(value.get("sha256"), str) and SHA256.fullmatch(value["sha256"]):
        result["sha256"] = value["sha256"]
    if value.get("format") in ("ELF", "script", "other"):
        result["format"] = value["format"]
    if value.get("linkage") in ("dynamic", "static", "not an ELF", "unparsed ELF class or byte order",
                                "no PT_INTERP; self-relocating/static PIE or self-loader"):
        result["linkage"] = value["linkage"]
    if type(value.get("elf_machine")) is int and value["elf_machine"] in (3, 40, 62, 183, 243):
        result["elf_machine"] = value["elf_machine"]
    if value.get("interpreter") == "/lib/ld-linux-aarch64.so.1":
        result["interpreter"] = value["interpreter"]
    elif "interpreter" in value:
        if value["interpreter"] is None:
            result["interpreter"] = None
        else:
            result["interpreter_status"] = "omitted_nonallowlisted_path"
    return result


def tool_version(name, value):
    if isinstance(value, list):
        value = value[0] if len(value) == 1 else None
    if not isinstance(value, str) or len(value) > 192:
        return None
    match = re.fullmatch(TOOL_VERSIONS[name], value)
    return match["version"] if match else None


def guest_metadata(facts):
    if not isinstance(facts, dict):
        return {}
    result = {key: facts[key] for key in ("page_size", "Seccomp", "Seccomp_filters")
              if (type(facts.get(key)) is int and 0 <= facts[key] <= 1048576) or
              (isinstance(facts.get(key), str) and re.fullmatch(r"[0-9]{1,7}", facts[key]))}
    if facts.get("architecture") in ("aarch64", "armv7l", "x86_64", "i686", "riscv64"):
        result["architecture"] = facts["architecture"]
    if isinstance(facts.get("python"), str) and re.fullmatch(VERSION, facts["python"]):
        result["python"] = facts["python"]
    distribution = facts.get("distribution")
    if isinstance(distribution, dict):
        result["distribution"] = {}
        if distribution.get("ID") in ("debian", "ubuntu", "alpine", "arch", "fedora", "centos", "rhel",
                                       "opensuse-leap", "opensuse-tumbleweed", "linuxmint", "kali", "raspbian", "android"):
            result["distribution"]["ID"] = distribution["ID"]
        if isinstance(distribution.get("VERSION_ID"), str) and re.fullmatch(r"[0-9]{1,4}(?:\.[0-9]{1,4}){0,3}", distribution["VERSION_ID"]):
            result["distribution"]["VERSION_ID"] = distribution["VERSION_ID"]
    release = facts.get("kernel_release")
    if isinstance(release, str) and len(release) <= 160:
        match = re.match(r"[0-9]{1,3}\.[0-9]{1,3}(?:\.[0-9]{1,4})?(?=$|[-+])", release)
        if match:
            normalized = match[0]
            tail = release[len(normalized):]
            for suffix in tail.split("-")[1:] if tail.startswith("-") else []:
                if not re.fullmatch(r"android[0-9]{1,2}|[0-9]{1,9}|g[0-9a-f]{7,40}|ab[0-9]{1,12}|"
                                    r"[0-9]{1,2}k|rc[0-9]{1,3}|gki|generic|aws|azure|raspi|arm64|amd64|dirty", suffix):
                    break
                normalized += "-" + suffix
            result["kernel_release"] = normalized
            if normalized != release:
                result["kernel_release_scope"] = "recognized_version_prefix_only"
    source_tools = facts.get("tools", {})
    if isinstance(source_tools, dict):
        result["tools"] = {}
        for name in ("git", "rg", "dpkg", "apt-get", "python3"):
            value = source_tools.get(name)
            if not isinstance(value, dict) or value.get("status") not in ("present", "missing", "probe-failed"):
                continue
            item = {"status": value["status"], **executable_metadata(value)}
            version = tool_version(name, value.get("version"))
            if version is not None:
                item["version"] = version
            elif value["status"] == "present":
                item["version_status"] = "not_recorded_or_unrecognized_format"
            result["tools"][name] = item
    return result


def cli_metadata(verified):
    result = {}
    if not isinstance(verified, dict):
        return result
    for name in ("codex", "claude"):
        value = verified.get(name)
        if not isinstance(value, dict) or value.get("status") not in ("passed", "failed", "skipped"):
            continue
        item = {"status": value["status"], "scope": "version/help only; authentication and real tasks untested"}
        executable = executable_metadata(value.get("executable"))
        if executable:
            item["executable"] = executable
        version = tool_version(name, value.get("version"))
        if version is not None:
            item["version"] = version
        elif value["status"] != "skipped":
            item["version_status"] = "not_recorded_in_worker_envelope" if "version" not in value else "unrecognized_version_format"
        # Existing workers record only the --version stdout digest, not its
        # contents. Never open a raw worker path or infer a version from a hash.
        phases = value.get("phases", [])
        for phase in (phases if isinstance(phases, list) else []):
            if not isinstance(phase, dict) or phase.get("name") != name + "--version":
                continue
            probe = {key: phase[key] for key in ("stdout_bytes", "exit_code") if type(phase.get(key)) is int and
                     0 <= phase[key] <= 64 * 1024 * 1024}
            if isinstance(phase.get("stdout_sha256"), str) and SHA256.fullmatch(phase["stdout_sha256"]):
                probe["stdout_sha256"] = phase["stdout_sha256"]
            if probe:
                item["version_probe"] = probe
        result[name] = item
    return result


def cases_from(values):
    require(isinstance(values, (list, tuple)), "expected cases must be an ordered array")
    cases = []
    for value in values:
        if isinstance(value, str):
            name, separator, workload = value.partition("=")
            require(separator, "--case must be JOB=CASE[:REPOSITORY]")
            case, _, repository = workload.partition(":")
            value = {"name": name, "case": case, "repository": repository}
        require(isinstance(value, dict), "each case must be a metadata object or JOB=CASE[:REPOSITORY]")
        name, case, repository = value.get("name"), value.get("case"), value.get("repository", "")
        require(isinstance(name, str) and TOKEN.fullmatch(name), "job name must be a bounded filename token")
        require(isinstance(case, str) and (case in ("repository", "python-verify", "local-replay", "two-workspaces",
                "offline-packages", "cli-probe") or re.fullmatch(r"(?:dpkg|apt)-[1-9][0-9]*", case)),
                "unsupported device case; authenticated agent runs are outside this collector's scope")
        require(isinstance(repository, str) and (not repository or TOKEN.fullmatch(repository)), "invalid repository name")
        if case in ("repository", "python-verify", "local-replay", "two-workspaces"):
            require(repository, "repository workloads must explicitly name their prepared repository")
        else:
            require(not repository, "this workload does not take a repository")
        cases.append({"name": name, "case": case, "repository": repository,
                      "case_key": case + (":" + repository if repository else "")})
    require(cases and len(cases) <= 64, "declare between 1 and 64 expected cases")
    require(len({item["name"] for item in cases}) == len(cases), "duplicate job names")
    require(len({item["case_key"] for item in cases}) == len(cases), "duplicate workload/repository cases")
    return cases


def read_bounded(path, limit):
    require(path.is_file() and not path.is_symlink(), "artifact must be a regular local file")
    require(path.stat().st_size <= limit, "artifact exceeds collector size limit")
    return path.read_bytes()


def diagnostic_reports(value):
    if isinstance(value, dict):
        if value.get("measurement_kind") == "diagnostic" or value.get("proot_totals") or value.get("reports"):
            return True
        return any(diagnostic_reports(item) for item in value.values())
    return isinstance(value, list) and any(diagnostic_reports(item) for item in value)


def check_phases(phases, issues):
    if not isinstance(phases, list):
        issues.append("invalid_worker_phases")
        return []
    clean = []
    for phase in phases:
        if not isinstance(phase, dict) or not isinstance(phase.get("name"), str) or not TOKEN.fullmatch(phase["name"]) or \
                not isinstance(phase.get("measured"), bool) or not number(phase.get("wall_ms")):
            issues.append("invalid_worker_phase")
            continue
        code = phase.get("exit_code")
        cancellation = phase["name"] == "cancel-and-reap" and phase.get("expected_cancellation") is True
        if type(code) is not int or code != (143 if cancellation else 0):
            issues.append("worker_phase_exit_failure")
        if phase.get("timed_out", False) is not False:
            issues.append("worker_phase_timeout")
        item = {key: phase[key] for key in ("name", "measured", "wall_ms", "exit_code") if key in phase}
        if phase.get("first_stdout_ms") is not None:
            if number(phase["first_stdout_ms"]):
                item["first_stdout_ms"] = phase["first_stdout_ms"]
            else:
                issues.append("invalid_first_stdout_time")
        for key in RESOURCE_METRICS:
            if key not in phase:
                continue
            value = phase[key]
            valid = value is None or (number(value) and
                    (key.endswith("_ms") or type(value) is int))
            if valid:
                item[key] = value
            else:
                issues.append("invalid_phase_resource")
        if any(key in item for key in RESOURCE_METRICS):
            item["cpu_scope"] = "launched process plus waited descendants; guest/tracer unseparated"
        for key in ("stdout_bytes", "stderr_bytes"):
            if type(phase.get(key)) is int and 0 <= phase[key] <= 64 * 1024 * 1024:
                item[key] = phase[key]
        for key in ("stdout_sha256", "stderr_sha256"):
            if isinstance(phase.get(key), str) and SHA256.fullmatch(phase[key]):
                item[key] = phase[key]
        clean.append(item)
    return clean


def worker_record(payload, expected, real_exit, issues):
    if not isinstance(payload, dict) or type(payload.get("schema")) is not int or payload["schema"] != SCHEMA:
        issues.append("invalid_worker_schema")
        return {"status": "failed"}
    status = payload.get("status")
    result = {"status": status if status in ("passed", "failed", "skipped") else "failed"}
    if status not in ("passed", "failed", "skipped"):
        issues.append("invalid_worker_status")
    if payload.get("case") != expected["case"]:
        issues.append("worker_case_mismatch")
    if real_exit is not None and real_exit != (1 if status == "failed" else 0):
        issues.append("status_exit_disagreement")
    if status == "failed":
        issues.append("worker_reported_failure")
    if status == "skipped":
        result["reason_code"] = "worker_reported_missing_input"
    result["guest_facts"] = guest_metadata(payload.get("facts"))
    if expected["case"] == "cli-probe":
        result["cli_support"] = cli_metadata(payload.get("verified"))
    if status == "passed":
        if payload.get("measurement_kind") not in ("timing", "diagnostic"):
            issues.append("invalid_measurement_kind")
        for key in ("measured_ms", "workflow_elapsed_ms", "preparation_ms", "verification_ms"):
            if number(payload.get(key)):
                result[key] = payload[key]
            else:
                issues.append("invalid_worker_timing")
        first = payload.get("first_measured_stdout_ms")
        if first is None or number(first):
            result["first_measured_stdout_ms"] = first
        else:
            issues.append("invalid_first_stdout_time")
        result["phases"] = check_phases(payload.get("phases"), issues)
        if not any(item["measured"] for item in result["phases"]) or payload.get("measured_ms") == 0:
            issues.append("passed_worker_has_no_measured_work")
        if number(payload.get("measured_ms")) and not math.isclose(payload["measured_ms"],
                sum(item["wall_ms"] for item in result["phases"] if item["measured"]), rel_tol=1e-6, abs_tol=0.001):
            issues.append("measured_total_disagrees_with_phases")
        if number(payload.get("workflow_elapsed_ms")) and number(payload.get("measured_ms")) and \
                payload["workflow_elapsed_ms"] + 0.001 < payload["measured_ms"]:
            issues.append("measured_time_exceeds_workflow")
        verified = payload.get("verified")
        if not isinstance(verified, dict) or not verified:
            issues.append("missing_worker_output_verification")
        elif expected["case"] == "two-workspaces":
            sessions = verified.get("sessions")
            if not isinstance(sessions, list) or len(sessions) != 2:
                issues.append("invalid_workspace_verification")
            else:
                result["workspace_phases"] = []
                for session in sessions:
                    result["workspace_phases"].append(check_phases(
                        session.get("phases") if isinstance(session, dict) else None, issues))
            cancel = verified.get("cancellation", {})
            if not isinstance(cancel, dict) or cancel.get("cancelled_and_reaped") is not True or \
                    cancel.get("fd_before") != cancel.get("fd_after"):
                issues.append("cancellation_verification_failed")
    return result


def collect_attempt(directory, pair, expected, mode, entries):
    name = f"{pair}-{expected['name']}-{mode}.log"
    row = {"pair": pair - 1, "mode": mode, "case_key": expected["case_key"],
           "job": expected["name"], "first_invocation_for_case": pair == 1, "log": name}
    issues = []
    real_exit = entries[0] if len(entries) == 1 else None
    row["real_exit"] = real_exit
    if not entries:
        issues.append("missing_status_row")
    elif len(entries) != 1:
        issues.append("duplicate_status_rows")
    if real_exit is not None and real_exit not in (0, 1):
        issues.append("launcher_exit_failure")
    envelopes, diagnostics = [], False
    try:
        raw = read_bounded(directory / name, 16 * 1024 * 1024)
        row["log_sha256"] = hashlib.sha256(raw).hexdigest()
        for line in raw.decode(errors="replace").splitlines():
            try:
                value = json.loads(line)
            except ValueError:
                if "rootless_workload" in line:
                    issues.append("malformed_worker_envelope")
                if FATAL.search(line):
                    issues.append("fatal_guest_signal_in_log")
                if TIMEOUT.search(line):
                    issues.append("timeout_in_log")
                diagnostics |= "proot-perf:" in line
                continue
            if isinstance(value, dict) and "rootless_workload" in value:
                envelopes.append(value["rootless_workload"])
            diagnostics |= isinstance(value, dict) and ("faststat" in value or diagnostic_reports(value))
    except (CheckFailure, OSError):
        issues.append("missing_or_invalid_log")
    if len(envelopes) != 1:
        issues.append("expected_exactly_one_worker_envelope")
        result = {"status": "failed"}
    else:
        result = worker_record(envelopes[0], expected, real_exit, issues)
    if issues:
        result["status"] = "failed"
        result["reason_codes"] = sorted(set(issues))
    row["diagnostics_observed"] = diagnostics
    row["result"] = result
    return row


def collect(directory, pairs, cases, instrumentation="unknown", smoke=False):
    require(type(pairs) is int and 1 <= pairs <= 1000, "expected pairs must be in 1..1000")
    require(instrumentation in ("disabled", "enabled", "unknown"), "invalid timing instrumentation declaration")
    directory = Path(directory)
    issues, inputs, rows, statuses, order = [], {}, [], {}, []
    expected_order = [(pair, case["name"], mode) for pair in range(1, pairs + 1) for case in cases
                      for mode in (("baseline", "candidate") if pair % 2 else ("candidate", "baseline"))]
    expected_keys = set(expected_order)
    try:
        for line in read_bounded(directory / "status.tsv", 16 * 1024 * 1024).decode().splitlines():
            pieces = line.split("\t")
            if len(pieces) != 4 or not pieces[0].isdigit() or not pieces[3].isdigit():
                issues.append("malformed_status_row")
                continue
            pair, name, mode, code = int(pieces[0]), pieces[1], pieces[2], int(pieces[3])
            key = (pair, name, mode)
            if key not in expected_keys or not 0 <= code <= 255:
                issues.append("unexpected_status_row")
                continue
            order.append(key)
            statuses.setdefault(key, []).append(code)
    except (CheckFailure, OSError, UnicodeError):
        issues.append("missing_or_invalid_status_file")
    order_valid = order == expected_order
    if not order_valid:
        issues.append("status_sequence_not_declared_alternating_order")
    try:
        done = read_bounded(directory / "done", 64).strip() == b"DONE"
    except (CheckFailure, OSError):
        done = False
    if not done:
        issues.append("batch_completion_marker_missing")
    try:
        for line in read_bounded(directory / "inputs.sha256", 32768).decode().splitlines():
            match = re.fullmatch(r"([0-9a-fA-F]{64})\s+\*?(.+)", line)
            if not match:
                issues.append("invalid_input_hash_row")
                continue
            name = Path(match[2]).name
            if name not in ARTIFACTS or name in inputs:
                issues.append("unexpected_or_duplicate_input_artifact")
                continue
            inputs[name] = match[1].lower()
    except (CheckFailure, OSError, UnicodeError):
        issues.append("missing_or_invalid_input_hashes")
    if set(inputs) != set(ARTIFACTS):
        issues.append("required_input_hash_missing")
    facts = {}
    for filename, key, pattern in (("android-api.txt", "android_api", r"[1-9][0-9]{0,2}"),
                                   ("selinux.txt", "selinux", r"Enforcing|Permissive|Disabled")):
        try:
            value = read_bounded(directory / filename, 64).decode().strip()
            require(re.fullmatch(pattern, value), "invalid platform fact")
            facts[key] = int(value) if key == "android_api" else value
        except (CheckFailure, OSError, UnicodeError):
            issues.append("missing_or_invalid_" + key)
    known_logs = {f"{pair}-{name}-{mode}.log" for pair, name, mode in expected_order}
    if any(path.name not in known_logs for path in directory.glob("*.log")):
        issues.append("unlisted_log_files")
    case_map = {case["name"]: case for case in cases}
    for pair, name, mode in expected_order:
        rows.append(collect_attempt(directory, pair, case_map[name], mode, statuses.get((pair, name, mode), [])))
    diagnostics = any(row["diagnostics_observed"] for row in rows)
    if instrumentation == "disabled" and diagnostics:
        issues.append("diagnostic_instrumentation_contradicts_disabled_declaration")
    if pairs < 10 and not smoke:
        issues.append("fewer_than_ten_pairs_requires_explicit_smoke_scope")
    eligible = not issues and not smoke and pairs >= 10 and instrumentation == "disabled" and not diagnostics
    summaries = summarize(rows, pairs, eligible)
    if "cli-probe" in summaries:
        summaries["cli-probe"].update(comparison_eligible=False, scope="version/help only; authentication and real tasks untested")
    failed = bool(issues) or any(row["result"]["status"] == "failed" for row in rows)
    skipped = any(row["result"]["status"] == "skipped" for row in rows)
    return {"schema": SCHEMA, "status": "failed" if failed else "completed-with-skips" if skipped else "completed",
            "scope": "device-correctness-smoke" if smoke else "device-diagnostic" if instrumentation == "enabled" or diagnostics
                     else "device-baseline-candidate-timing" if instrumentation == "disabled" else "device-correctness-only",
            "pairs": pairs, "cases": cases, "batch_done": done, "alternating_order_valid": order_valid,
            "input_errors": sorted(set(issues)), "input_sha256": inputs, "platform": facts,
            "instrumentation": {"declared": instrumentation, "diagnostics_observed": diagnostics},
            "cache_condition": "fixture preparation and preconditions warm cache; first invocations labeled, never claimed cold",
            "resource_scope": "per-command wait4 only; guest/tracer CPU unseparated; missing metrics remain unavailable; RSS is not summed",
            "actual_agent": {"status": "skipped", "scope": "no authentication or model requests; any CLI evidence is version/help only"},
            "path_scope": "allowlisted fields only; raw device paths, command argv and worker log paths omitted",
            "rows": rows, "summaries": summaries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="pulled device-batch result directory")
    parser.add_argument("--output", required=True)
    parser.add_argument("--metadata", help="explicit batch JSON containing schema, pairs and ordered cases")
    parser.add_argument("--pairs", type=int, help="expected pair count, never inferred from completed rows")
    parser.add_argument("--case", action="append", help="repeat JOB=CASE[:REPOSITORY] in batch case order")
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--timing-instrumentation-disabled", action="store_true",
                        help="operator confirms selected runner disabled timing counters; logs are checked independently")
    args = parser.parse_args()
    try:
        metadata = json.loads(read_bounded(Path(args.metadata), 65536)) if args.metadata else {}
        require(isinstance(metadata, dict) and metadata.get("schema", SCHEMA) == SCHEMA, "unsupported batch metadata")
        require(type(metadata.get("smoke", False)) is bool, "metadata smoke must be boolean")
        pairs = args.pairs if args.pairs is not None else metadata.get("pairs")
        values = args.case if args.case is not None else metadata.get("cases", [])
        cases = cases_from(values)
        if args.pairs is not None and "pairs" in metadata:
            require(args.pairs == metadata["pairs"], "CLI and metadata pair counts disagree")
        if args.case is not None and "cases" in metadata:
            require(cases == cases_from(metadata["cases"]), "CLI and metadata cases disagree")
        instrumentation = metadata.get("timing_instrumentation", "unknown")
        if args.timing_instrumentation_disabled:
            require(instrumentation in ("unknown", "disabled"), "instrumentation declarations disagree")
            instrumentation = "disabled"
        result = collect(args.input, pairs, cases, instrumentation, args.smoke or metadata.get("smoke", False))
        write_json(args.output, result)
        print(json.dumps({"status": result["status"], "attempts": len(result["rows"]),
                          "eligible_cases": sum(value["comparison_eligible"] for value in result["summaries"].values())}))
        return 1 if result["status"] == "failed" else 0
    except (CheckFailure, OSError, ValueError) as error:
        # File-system exceptions can include app cache paths; keep diagnostics
        # generic. Protocol errors above are fixed strings, never raw input.
        print("device collector: invalid configuration or inaccessible input/output" if isinstance(error, OSError)
              else "device collector: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
