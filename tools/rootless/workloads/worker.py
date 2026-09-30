#!/usr/bin/env python3
"""Run one verified, disposable workload inside the selected Linux runtime."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import stat
import subprocess
import sys
import time

from common import (SCHEMA, CheckFailure, MissingInput, check_exit, child_environment,
                    digest, executable_facts, execute, file_digest, parse_counters, process_identity,
                    require, stop_process, validate_agent_config, write_json)
from fixtures import (GIT, NEEDLE, PACKAGE, PACKAGE_TIME, package_payload,
                      patch_project, rg_commands, safe_extract)


class Run:
    def __init__(self, work, env=None):
        self.work = Path(work)
        self.logs = self.work / "logs"
        self.logs.mkdir(parents=True)
        self.home = self.work / "home"
        self.home.mkdir()
        self.env = child_environment(self.home) if env is None else env
        self.phases = []
        self.verification_ms = 0
        self.preparation_ms = 0

    def command(self, name, argv, cwd, measured=True, expected=(0,), timeout=120):
        index = len(list(self.logs.iterdir())) // 2
        result = execute(argv, cwd, env=self.env, timeout=timeout,
                         stdout_path=self.logs / f"{index:03}-{name}.stdout",
                         stderr_path=self.logs / f"{index:03}-{name}.stderr")
        result.update(name=name, measured=measured)
        result["counters"] = parse_counters(Path(result["stderr_file"]).read_text(errors="replace"))
        self.phases.append(result)
        check_exit(result, expected)
        return result

    def output(self, result):
        return Path(result["stdout_file"]).read_bytes()

    def expected(self, result, oracle):
        require(result["stdout_sha256"] == oracle["sha256"] and result["stdout_bytes"] == oracle["bytes"],
                f"{result['name']} output differs from prepared native oracle (even though exit was {result['exit_code']})")

    def verify(self, function, *args):
        start = time.monotonic_ns()
        try:
            return function(*args)
        finally:
            self.verification_ms += (time.monotonic_ns() - start) / 1e6


def need_tools(*names):
    missing = [name for name in names if not shutil.which(name)]
    if missing:
        raise MissingInput("required guest tools are not installed: " + ", ".join(missing))


def runtime_facts():
    facts = {"kernel_release": platform.release(), "architecture": platform.machine(),
             "page_size": os.sysconf("SC_PAGESIZE"), "python": platform.python_version(),
             "uid": os.getuid(), "tools": {}, "resource_scope": "process and waited descendants"}
    try:
        for line in Path("/proc/self/status").read_text().splitlines():
            key, _, value = line.partition(":")
            if key in ("Seccomp", "Seccomp_filters", "CapEff"):
                facts[key] = value.strip()
    except OSError:
        facts["proc_status"] = "unavailable"
    try:
        facts["selinux_enforcing"] = Path("/sys/fs/selinux/enforce").read_text().strip() == "1"
    except OSError:
        facts["selinux_enforcing"] = None
    try:
        facts["distribution"] = {line.split("=", 1)[0]: line.split("=", 1)[1].strip('"')
            for line in Path("/etc/os-release").read_text().splitlines()
            if line.startswith(("ID=", "VERSION_ID="))}
    except OSError:
        facts["distribution"] = None
    for name in ("git", "rg", "dpkg", "apt-get", "python3"):
        path = shutil.which(name)
        if not path:
            facts["tools"][name] = {"status": "missing"}
            continue
        try:
            version = subprocess.run([path, "--version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     env=child_environment(), timeout=10)
            facts["tools"][name] = {"status": "present", **executable_facts(path),
                "version": version.stdout.decode(errors="replace").splitlines()[:1]}
        except (OSError, subprocess.TimeoutExpired) as error:
            facts["tools"][name] = {"status": "probe-failed", "reason": str(error)}
    return facts


def repository(run, bundle, manifest, name, destination="repository"):
    need_tools("git")
    if name not in manifest["repositories"]:
        raise MissingInput("pinned repository input was not explicitly prepared: " + name)
    spec = manifest["repositories"][name]
    source = bundle / spec["archive"]
    require(file_digest(source) == spec["sha256"], "repository archive digest mismatch")
    tree = run.work / destination
    tree.mkdir()
    safe_extract(source, tree)
    head = run.command("prepared-head", GIT + ["rev-parse", "HEAD"], tree, measured=False)
    require(run.output(head).decode().strip() == spec["fixture_commit"], "fixture commit changed")
    # Refresh index stat data after extraction outside measured status calls.
    refreshed = run.command("prepared-status", GIT + ["status", "--porcelain=v1", "--untracked-files=all"], tree, measured=False)
    run.expected(refreshed, spec["expected"]["git-status"])
    return tree, spec


def repository_checks(run, tree, spec):
    need_tools("git", "rg")
    if "rg-list" not in spec["expected"]:
        raise MissingInput("bundle was prepared without rg output oracle")
    commands = {"git-status": GIT + ["status", "--porcelain=v1", "--untracked-files=all"],
                "git-diff": GIT + ["diff", "--no-ext-diff", "--binary", "--", "."], **rg_commands()}
    for name, argv in commands.items():
        result = run.command(name, argv, tree)
        run.verify(run.expected, result, spec["expected"][name])
    # Git and rg deliberately leave the external-target symlink untraversed;
    # verify the actual symlink and intended read target independently.
    require((tree / "bench-link").is_symlink() and os.readlink(tree / "bench-link") == "bench_project/README.txt",
            "repository symlink semantics changed")
    return {"verified_outputs": sorted(commands), "repository": spec["provenance"]}


def prepare_python(run, tree):
    for module in ("ssl", "sqlite3", "asyncio"):
        if importlib.util.find_spec(module) is None:
            raise MissingInput("Python standard library module missing: " + module)
    project = tree / "bench_project"
    result = run.command("prepare-incremental", [sys.executable, "-B", "build.py"], project, measured=False)
    state = json.loads(run.output(result))
    require(len(state["rebuilt"]) == 65 and state["sources"] == 65, "initial incremental build did not compile all sources")
    result = run.command("prepare-noop", [sys.executable, "-B", "build.py"], project, measured=False)
    require(json.loads(run.output(result)) == {"rebuilt": [], "sources": 65}, "no-op build was not incremental")
    return project


def python_checks(run, tree, spec, project=None):
    project = project or prepare_python(run, tree)
    begin = time.monotonic_ns()
    patch_project(tree)
    run.phases.append({"name": "atomic-edit", "measured": True,
                       "wall_ms": (time.monotonic_ns() - begin) / 1e6, "exit_code": 0})
    result = run.command("python-import", [sys.executable, "-I", "-B", "-c",
        "import asyncio,email.mime.text,http.client,json,sqlite3,ssl,tarfile,urllib.request,xml.etree.ElementTree,zipfile;print('imports-ok')"], project)
    require(run.output(result) == b"imports-ok\n", "Python imports did not complete")
    result = run.command("selected-tests", [sys.executable, "-B", "run_tests.py"], project)
    require(json.loads(run.output(result)) == {"tests": 3, "failures": 0, "errors": 0}, "selected tests did not run all 3 tests")
    result = run.command("incremental-build", [sys.executable, "-B", "build.py"], project)
    require(json.loads(run.output(result)) == {"rebuilt": ["calc.py"], "sources": 65}, "incremental build rebuilt wrong sources")
    require(file_digest(project / "calc.py") == spec["patched_calc_sha256"], "final source digest mismatch")
    build_manifest = json.loads((project / "build/manifest.json").read_text())
    require(build_manifest["calc.py"] == spec["patched_calc_sha256"], "build did not consume edited source")
    result = run.command("git-diff-check", GIT + ["diff", "--check"], tree)
    require(run.output(result) == b"", "git diff --check was not clean")
    result = run.command("git-diff-patched", GIT + ["diff", "--no-ext-diff", "--binary", "--", "."], tree)
    run.expected(result, spec["expected"]["git-diff-patched"])
    return {"tests": 3, "incremental_sources_rebuilt": ["calc.py"],
            "patched_source_sha256": file_digest(project / "calc.py"),
            "build_manifest_sha256": file_digest(project / "build/manifest.json")}


def replay(run, tree, spec, prepared_project=None):
    repository_checks(run, tree, spec)
    paths = ["bench_project/README.txt", "bench_project/calc.py", "bench_project/test_calc.py"]
    expected = {name: file_digest(tree / name) for name in paths}
    result = run.command("read-selected-files", [sys.executable, "-I", "-B", "-c",
        "import hashlib,json,pathlib,sys; print(json.dumps({p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for p in sys.argv[1:]},sort_keys=True))", *paths], tree)
    require(json.loads(run.output(result)) == expected, "selected file contents were not read correctly")
    result = python_checks(run, tree, spec, prepared_project)
    result["scope"] = "fixed local tool replay; no model inference, authentication or agent task completion"
    return result


def package_root(work):
    target = work / "target"
    admin = target / "var/lib/dpkg"
    (admin / "updates").mkdir(parents=True)
    (admin / "status").write_text("")
    return target, admin


def verify_package(target, count, version):
    directory = target / "usr/share" / PACKAGE
    paths = list(directory.glob("*.txt"))
    require(len(paths) == count, "installed package file count differs")
    content_hash = __import__("hashlib").sha256()
    for i in range(count):
        path = directory / f"{i:05}.txt"
        expected = package_payload(version, i)
        actual = path.read_bytes()
        require(actual == expected, f"package payload differs at file {i}")
        info = path.stat()
        require(stat.S_IMODE(info.st_mode) == (0o640 if i % 11 == 0 else 0o644), "installed mode differs")
        require(int(info.st_mtime) == PACKAGE_TIME, "installed mtime differs")
        content_hash.update(actual)
    original, linked = (directory / "00000.txt").stat(), (directory / "hardlink").stat()
    require(original.st_ino == linked.st_ino and original.st_dev == linked.st_dev and original.st_nlink == 2,
            "package hardlink identity/nlink differs")
    require((directory / "symlink").is_symlink() and os.readlink(directory / "symlink") == "00000.txt",
            "package symlink differs")
    status = (target / "var/lib/dpkg/status").read_text()
    require(f"Package: {PACKAGE}\n" in status and f"Version: {version}\n" in status and
            "Status: install ok installed\n" in status, "package database does not show configured expected version")
    return {"files": count, "version": version, "payload_sha256": content_hash.hexdigest(),
            "hardlink_nlink": original.st_nlink, "observed_uid": original.st_uid,
            "uid_contract": "ordinary host UID outside PRoot; selected fake-root identity inside PRoot"}


def apt_options(work, target, admin):
    # Empty source lists plus --no-download: installed system package state,
    # network repositories and user APT hooks cannot enter the measurement.
    apt = work / "apt"
    for name in ("state/lists/partial", "cache/archives/partial", "etc/parts", "etc/sources", "etc/preferences.d", "log"):
        (apt / name).mkdir(parents=True)
    (apt / "etc/empty.conf").write_text("")
    (apt / "etc/sources.list").write_text("")
    # APT_CONFIG is loaded before the normal host apt.conf.d directory. Command
    # line Dir settings alone are too late to prevent inherited system hooks.
    (apt / "bootstrap.conf").write_text(
        f'Dir::Etc::main "{apt / "etc/empty.conf"}";\n'
        f'Dir::Etc::parts "{apt / "etc/parts"}";\n'
        '#clear DPkg::Pre-Invoke;\n#clear DPkg::Post-Invoke;\n#clear DPkg::Pre-Install-Pkgs;\n')
    options = ["apt-get", "-y", "--no-download", "--no-install-recommends",
        "-o", f"Dir={apt}", "-o", f"Dir::State={apt / 'state'}",
        "-o", f"Dir::State::status={admin / 'status'}", "-o", f"Dir::Cache={apt / 'cache'}",
        "-o", f"Dir::Log={apt / 'log'}", "-o", f"Dir::Etc={apt / 'etc'}",
        "-o", f"Dir::Etc::main={apt / 'etc/empty.conf'}", "-o", f"Dir::Etc::parts={apt / 'etc/parts'}",
        "-o", f"Dir::Etc::sourcelist={apt / 'etc/sources.list'}", "-o", f"Dir::Etc::sourceparts={apt / 'etc/sources'}",
        "-o", "APT::Get::List-Cleanup=0",
        "-o", "Dpkg::Use-Pty=0", "-o", "DPkg::Options::=--force-not-root",
        "-o", f"DPkg::Options::=--root={target}", "-o", f"DPkg::Options::=--log={work / 'dpkg.log'}"]
    return options


def packages(run, bundle, manifest, count, frontend):
    preparation_start = time.monotonic_ns()
    need_tools("dpkg", *( ["apt-get"] if frontend == "apt" else []))
    if os.geteuid() != 0:
        raise MissingInput("package ownership requires selected Rootless fake-root identity; ordinary native UID control is insufficient")
    if str(count) not in manifest["packages"]:
        raise MissingInput("package count was not prepared: " + str(count))
    specs = manifest["packages"][str(count)]
    for spec in specs:
        require(file_digest(bundle / spec["path"]) == spec["sha256"], "package archive digest mismatch")
    target, admin = package_root(run.work)
    dpkg = ["dpkg", "--force-not-root", "--root=" + str(target), "--log=" + str(run.work / "dpkg.log")]
    apt = apt_options(run.work, target, admin) if frontend == "apt" else None
    if apt:
        run.env["APT_CONFIG"] = str(run.work / "apt/bootstrap.conf")
        for spec in specs:
            shutil.copyfile(bundle / spec["path"], run.work / "apt/cache/archives" / f"{PACKAGE}_{spec['version']}_all.deb")
    run.preparation_ms += (time.monotonic_ns() - preparation_start) / 1e6
    checks = []
    for number, spec in enumerate(specs):
        phase = "install" if number == 0 else "upgrade"
        if apt:
            run.command(phase, apt + ["install", str(bundle / spec["path"])], run.work, timeout=300)
        else:
            run.command(phase + "-unpack", dpkg + ["--unpack", str(bundle / spec["path"])], run.work, timeout=300)
            run.command(phase + "-configure", dpkg + ["--configure", PACKAGE], run.work, timeout=120)
        checks.append(run.verify(verify_package, target, count, spec["version"]))
    run.command("remove", (apt + ["remove", PACKAGE]) if apt else dpkg + ["--remove", PACKAGE], run.work, timeout=300)
    require(not (target / "usr/share" / PACKAGE).exists(), "package data remained after removal")
    status = (admin / "status").read_text()
    require("Status: install ok installed" not in status, "package remains installed after remove")
    return {"frontend": frontend, "count": count, "installed_versions": checks,
            "removed": True, "normal_fsync": "unchanged; no --force-unsafe-io", "network": "disabled by empty sources and --no-download"}


def offline_packages(run, bundle, manifest):
    need_tools("dpkg")
    inputs = manifest.get("offline_debs", [])
    if not inputs:
        raise MissingInput("no explicit hash-pinned real offline .deb input set was prepared")
    if os.geteuid() != 0:
        raise MissingInput("real package unpack requires the selected Rootless fake-root identity")
    names = [item["package"] for item in inputs]
    require(len(names) == len(set(names)), "offline unpack set contains multiple versions of one package")
    for item in inputs:
        require(file_digest(bundle / item["path"]) == item["sha256"], "real offline package digest mismatch")
        if item["maintainer_entries"] or any(entry["kind"] == "unsupported-special" for entry in item["payload"]):
            raise MissingInput("real package needs maintainer/special-node provisioned-root gate: " + item["package"])
    preparation = time.monotonic_ns()
    target, admin = package_root(run.work)
    base = ["dpkg", "--force-not-root", "--root=" + str(target), "--log=" + str(run.work / "dpkg.log")]
    run.preparation_ms += (time.monotonic_ns() - preparation) / 1e6
    run.command("real-package-unpack", base + ["--unpack", *[str(bundle / item["path"]) for item in inputs]], run.work, timeout=300)
    verification = time.monotonic_ns()
    for item in inputs:
        for entry in item["payload"]:
            path = target / entry["path"]
            info = path.lstat()
            if entry["kind"] == "symlink":
                require(stat.S_ISLNK(info.st_mode) and os.readlink(path) == entry["target"], "real package symlink differs")
                continue
            require(path.resolve().is_relative_to(target.resolve()), "real package file resolves outside isolated target")
            require(stat.S_IMODE(info.st_mode) == entry["mode"] and info.st_mtime == entry["mtime"], "real package mode/mtime differs")
            if entry["kind"] == "file":
                require(info.st_size == entry["size"] and file_digest(path) == entry["sha256"], "real package contents differ")
            else:
                other = (target / entry["target"]).stat()
                require(info.st_ino == other.st_ino and info.st_dev == other.st_dev, "real package hardlink differs")
    status = (admin / "status").read_text()
    require(all(f"Package: {name}\n" in status for name in names), "real package DB entries missing")
    require(status.count("Status: install ok unpacked\n") == len(inputs), "real packages not in expected unpacked state")
    run.verification_ms += (time.monotonic_ns() - verification) / 1e6
    run.command("real-package-remove", base + ["--remove", *names], run.work, timeout=300)
    status = (admin / "status").read_text()
    require("Status: install ok unpacked\n" not in status, "real package remains unpacked after removal")
    return {"packages": [{key: item[key] for key in ("package", "version", "sha256")} for item in inputs],
            "scope": "actual script-free pinned package unpack, payload/mode/time/link/DB verification and removal; dependency configuration untested",
            "network": "none"}


CANCEL_PROGRAM = r'''import json,os,signal,subprocess,sys,time
stop=False
def cancelled(signum,frame):
    global stop
    stop=True
signal.signal(signal.SIGTERM,cancelled)
child=subprocess.Popen([sys.executable,'-I','-B','-c','import time;time.sleep(300)'])
print(json.dumps({'ready':True,'child':child.pid}),flush=True)
while not stop: time.sleep(.01)
child.terminate()
try: child.wait(timeout=3)
except subprocess.TimeoutExpired: child.kill();child.wait()
print(json.dumps({'reaped':child.pid,'returncode':child.returncode}),flush=True)
sys.exit(143)
'''


def cancel_and_reap(run):
    import selectors
    before = len(list(Path("/proc/self/fd").iterdir())) if Path("/proc/self/fd").is_dir() else None
    helper = run.work / "cancel-helper.py"
    helper.write_text(CANCEL_PROGRAM)
    output = run.work / "cancel.stdout"
    start = time.monotonic_ns()
    process = subprocess.Popen([sys.executable, "-I", "-B", str(helper)], cwd=run.work, env=run.env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL, start_new_session=True)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        require(selector.select(10), "cancel helper did not become ready")
        ready = json.loads(process.stdout.readline())
        require(ready.get("ready") is True and isinstance(ready.get("child"), int), "invalid cancellation readiness")
        identity = process_identity(ready["child"])
        process.terminate()
        try:
            stdout, stderr = process.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            stop_process(process)
            raise CheckFailure("cancel helper did not reap its child")
        output.write_bytes(json.dumps(ready).encode() + b"\n" + stdout)
        require(process.returncode == 143 and not stderr, "cancellation helper did not report deliberate termination")
        complete = json.loads(stdout)
        require(complete.get("reaped") == ready["child"] and complete.get("returncode") == -signal.SIGTERM,
                "cancelled child was not waited/reaped")
        if identity is not None:
            current = process_identity(ready["child"])
            require(current is None or current["start"] != identity["start"], "cancelled child remains in proc, including zombie")
    finally:
        selector.close()
        process.stdout.close()
        process.stderr.close()
        if process.poll() is None:
            stop_process(process)
    after = len(list(Path("/proc/self/fd").iterdir())) if before is not None else None
    require(before is None or before == after, "FD count grew after cancellation")
    elapsed = (time.monotonic_ns() - start) / 1e6
    run.phases.append({"name": "cancel-and-reap", "measured": True, "wall_ms": elapsed,
                       "exit_code": 143, "expected_cancellation": True})
    return {"cancelled_and_reaped": True, "fd_before": before, "fd_after": after,
            "child_absence_observed": identity is not None, "scope": "local tool process tree, not authenticated agent cancellation"}


def concurrent(run, bundle, manifest, repository_name):
    preparation_start = time.monotonic_ns()
    workers = []
    for number in range(2):
        directory = run.work / f"session-{number}"
        directory.mkdir()
        selected = Run(directory)
        tree, spec = repository(selected, bundle, manifest, repository_name)
        project = prepare_python(selected, tree)
        workers.append((selected, tree, spec, project))
    run.preparation_ms += (time.monotonic_ns() - preparation_start) / 1e6
    start = time.monotonic_ns()
    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(replay, *values) for values in workers]
        results = [future.result() for future in futures]
    elapsed = (time.monotonic_ns() - start) / 1e6
    run.phases.append({"name": "two-workspaces", "measured": True, "wall_ms": elapsed,
                       "exit_code": 0, "scope": "concurrent workflow wall time including output checks; never sum overlapping phase times"})
    cancellation = cancel_and_reap(run)
    for selected, tree, spec, _ in workers:
        require(file_digest(tree / "bench_project/calc.py") == spec["patched_calc_sha256"], "workspace modified by another session/cancellation")
        restarted = selected.command("post-cancel-tests", [sys.executable, "-B", "run_tests.py"], tree / "bench_project", measured=False)
        require(json.loads(selected.output(restarted)) == {"tests": 3, "errors": 0, "failures": 0}, "post-cancel test restart failed")
    return {"sessions": [{"result": result, "phases": selected.phases} for result, (selected, *_rest) in zip(results, workers)],
            "cancellation": cancellation, "isolation": "two separate copies; final sources and post-cancel tests verified"}


def cli_probe(run, tools):
    result = {}
    for name in tools:
        executable = shutil.which(name)
        if executable is None:
            result[name] = {"status": "skipped", "reason": "CLI is not installed on selected guest PATH"}
            continue
        records = []
        for flag in ("--version", "--help"):
            phase = run.command(name + flag, [executable, flag], run.work, timeout=30)
            require(phase["stdout_bytes"] > 0, "CLI probe returned empty output")
            records.append(phase)
        result[name] = {"status": "passed", "scope": "version/help only; authentication and real task completion untested",
                        "executable": executable_facts(executable), "phases": records}
    return result


def fixture_files(tree):
    """Snapshot fixture bytes/types, including ignored and untracked files."""
    result = {}
    for directory, children, files in os.walk(tree, followlinks=False):
        parent = Path(directory)
        children[:] = [name for name in children if parent != tree or name != ".git"]
        for name in [*files, *(name for name in children if (parent / name).is_symlink())]:
            path = parent / name
            info = path.lstat()
            relative = path.relative_to(tree).as_posix()
            if stat.S_ISLNK(info.st_mode):
                result[relative] = {"kind": "symlink", "target": os.readlink(path)}
            else:
                require(stat.S_ISREG(info.st_mode), "unexpected special file in agent fixture")
                result[relative] = {"kind": "file", "mode": stat.S_IMODE(info.st_mode), "sha256": file_digest(path)}
    return result


def authenticated_agent(run, tree, spec, job):
    agent = job.get("agent")
    if not job.get("enable_authenticated_run") or not agent:
        raise MissingInput("actual agent run requires explicit --enable-authenticated-run and prepared operator configuration")
    validate_agent_config(agent)
    name, argv = agent["name"], agent["argv"]
    if shutil.which(argv[0]) is None:
        raise MissingInput("configured agent CLI is not installed")
    for relative in agent["expected_files"]:
        require((tree / relative).resolve().is_relative_to(tree.resolve()), "expected agent output escapes fixture")
    require(any(not (tree / relative).is_file() or file_digest(tree / relative) != sha
                for relative, sha in agent["expected_files"].items()), "agent task is already satisfied before execution")
    # Credentials may already be provided to the CLI by an operator-selected
    # runtime/env. This harness neither discovers nor copies authentication.
    before = fixture_files(tree)
    previous = run.env
    inherited = agent.get("inherit_environment", [])
    if any(key not in os.environ for key in inherited):
        raise MissingInput("operator-selected agent environment is not provisioned")
    run.env = {**run.env, **{key: os.environ[key] for key in inherited}}
    try:
        result = run.command("authenticated-" + name, argv, tree, timeout=agent.get("timeout_seconds", 600))
    finally:
        run.env = previous
    events = []
    for line in run.output(result).splitlines():
        try:
            value = json.loads(line)
        except ValueError as error:
            raise CheckFailure("agent stdout is not the configured JSON event stream") from error
        require(isinstance(value, dict), "agent JSON event is not an object")
        events.append(value)
    require(events, "agent emitted no JSON events")
    expected = agent["expected_files"]
    for relative, sha in expected.items():
        path = (tree / relative).resolve()
        require(path.is_relative_to(tree.resolve()) and path.is_file(), "agent expected file missing or outside fixture")
        require(file_digest(path) == sha, "agent task final file hash mismatch: " + relative)
    after = fixture_files(tree)
    names = {name for name in before.keys() | after.keys() if before.get(name) != after.get(name)}
    allowed = set(agent.get("allowed_changed_files", expected))
    require(names <= allowed, "agent changed files outside declared task scope")
    verify = run.command("agent-task-verification", agent["verify_argv"], tree, measured=False,
                         timeout=agent.get("verification_timeout_seconds", 120))
    require(verify["stdout_sha256"] == agent["verify_stdout_sha256"], "agent verification output differs")
    run.command("agent-final-diff", GIT + ["diff", "--no-ext-diff", "--binary", "HEAD"], tree, measured=False)
    return {"status": "passed", "scope": "actual configured model-backed agent run plus independent artifact/test verification",
            "agent": name, "events": len(events), "final_files": expected, "changed_files": sorted(names),
            "model_network_auth_wait": "not separable from total; never inferred by subtracting overlapping tool times",
            "resume_interactive_pty": "not tested by this noninteractive runner"}


def run_job(job):
    work = Path(job["work"]).resolve()
    bundle = Path(job["bundle"]).resolve()
    manifest = json.loads((bundle / "manifest.json").read_text())
    require(manifest["schema"] == SCHEMA, "unsupported bundle schema")
    for name, expected in manifest["worker_sources"].items():
        require(file_digest(bundle / name) == expected, "bundled worker source digest changed")
    run = Run(work)
    result = {"schema": SCHEMA, "case": job["case"], "status": "failed",
              "measurement_kind": "diagnostic" if job.get("diagnostic") else "timing",
              "cache_condition": manifest["cache_condition"]}
    start = time.monotonic_ns()
    preparation_done = None
    try:
        case = job["case"]
        if case == "offline-packages":
            result["verified"] = offline_packages(run, bundle, manifest)
        elif case.startswith(("dpkg-", "apt-")):
            frontend, count = case.split("-", 1)
            result["verified"] = packages(run, bundle, manifest, int(count), frontend)
        elif case == "cli-probe":
            result["verified"] = cli_probe(run, ("codex", "claude"))
            if all(value["status"] == "skipped" for value in result["verified"].values()):
                raise MissingInput("neither CLI is installed; see per-CLI results")
        elif case == "two-workspaces":
            result["verified"] = concurrent(run, bundle, manifest, job.get("repository", "synthetic-10000"))
        elif case in ("repository", "python-verify", "local-replay", "agent-run"):
            tree, spec = repository(run, bundle, manifest, job.get("repository", "synthetic-10000"))
            project = prepare_python(run, tree) if case in ("python-verify", "local-replay") else None
            preparation_done = time.monotonic_ns()
            if case == "repository":
                result["verified"] = repository_checks(run, tree, spec)
            elif case == "python-verify":
                result["verified"] = python_checks(run, tree, spec, project)
            elif case == "local-replay":
                result["verified"] = replay(run, tree, spec, project)
            else:
                result["verified"] = authenticated_agent(run, tree, spec, job)
        else:
            raise CheckFailure("unknown workload case: " + case)
        result["status"] = "passed"
    except MissingInput as error:
        result.update(status="skipped", reason=str(error))
    except (CheckFailure, OSError, ValueError, subprocess.SubprocessError) as error:
        result.update(status="failed", reason=str(error))
    result["workflow_elapsed_ms"] = (time.monotonic_ns() - start) / 1e6
    result["preparation_ms"] = ((preparation_done - start) / 1e6 if preparation_done else run.preparation_ms)
    result["preparation_scope"] = "full fixture extraction/setup; source generation belongs to prepare command"
    result["verification_ms"] = run.verification_ms
    result["phases"] = run.phases
    result["measured_ms"] = sum(phase["wall_ms"] for phase in run.phases if phase["measured"])
    result["measured_scope"] = "sum of disjoint measured operations; two-workspaces uses one enclosing concurrent wall interval"
    result["workflow_unattributed_ms"] = max(0, result["workflow_elapsed_ms"] - result["preparation_ms"] -
                                             result["measured_ms"] - result["verification_ms"])
    result["workflow_unattributed_scope"] = "remaining validation/bookkeeping/scheduling; not inferred disk or model wait"
    result["first_measured_stdout_ms"] = next((phase.get("first_stdout_ms") for phase in run.phases
                                               if phase["measured"] and phase.get("first_stdout_ms") is not None), None)
    # Version probes fork. Probe after all timed work so metadata collection
    # cannot revoke the parent's temporary-FD eligibility before extraction.
    # Apply the same ordering to both engines; fixtures and output checks stay
    # unchanged. These probes are outside workflow_elapsed_ms as before.
    result["facts"] = runtime_facts()
    result["facts_probe_scope"] = "after workload; excluded from command and worker workflow timing"
    write_json(work / "result.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--job", required=True)
    args = parser.parse_args()
    job = json.loads(Path(args.job).read_text())
    try:
        result = run_job(job)
    except (OSError, CheckFailure, ValueError) as error:
        result = {"schema": SCHEMA, "case": job.get("case"), "status": "failed", "reason": str(error)}
    print(json.dumps({"rootless_workload": result}, sort_keys=True), flush=True)
    return 1 if result["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
