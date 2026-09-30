#!/usr/bin/env python3
"""Shared, standard-library-only helpers for the offline workload harness."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import resource
import selectors
import signal
import struct
import subprocess
import time


SCHEMA = 1


class CheckFailure(RuntimeError):
    pass


class MissingInput(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailure(message)


def validate_agent_config(agent):
    require(isinstance(agent, dict), "agent config must be a JSON object")
    require("environment" not in agent,
            "do not copy credentials into jobs; use profile env and inherit_environment key names")
    require(agent.get("name") in ("codex", "claude"), "agent name must be codex or claude")
    for key in ("argv", "verify_argv"):
        values = agent.get(key)
        require(isinstance(values, list) and values and all(isinstance(value, str) for value in values),
                key + " must be a nonempty JSON string array")
    expected = agent.get("expected_files")
    require(isinstance(expected, dict) and expected, "actual agent runs require expected file hashes")
    require(all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) for value in expected.values()),
            "expected file hashes must be SHA256 hex strings")
    require(isinstance(agent.get("verify_stdout_sha256"), str) and
            re.fullmatch(r"[0-9a-f]{64}", agent["verify_stdout_sha256"]),
            "agent verification must declare expected stdout SHA256 before any model run")
    allowed = agent.get("allowed_changed_files", list(expected))
    require(isinstance(allowed, list), "allowed_changed_files must be a JSON path array")
    for relative in [*expected, *allowed]:
        require(isinstance(relative, str) and relative and not Path(relative).is_absolute() and
                ".." not in Path(relative).parts and ".git" not in Path(relative).parts,
                "agent output paths must remain inside the fixture outside .git")
    require(set(expected) <= set(allowed), "expected files must be included in allowed_changed_files")
    inherited = agent.get("inherit_environment", [])
    require(isinstance(inherited, list) and all(isinstance(key, str) and
            re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", key) for key in inherited),
            "agent inherit_environment must contain environment key names only")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def executable_facts(path):
    facts = {"sha256": file_digest(path)}
    with Path(path).open("rb") as stream:
        header = stream.read(64)
        if not header.startswith(b"\x7fELF"):
            facts.update(format="script" if header.startswith(b"#!") else "other", linkage="not an ELF")
            return facts
        facts["format"] = "ELF"
        if header[4:6] != b"\x02\x01":
            facts["linkage"] = "unparsed ELF class or byte order"
            return facts
        facts["elf_machine"] = int.from_bytes(header[18:20], "little")
        offset = struct.unpack_from("<Q", header, 32)[0]
        size, count = struct.unpack_from("<HH", header, 54)
        require(size >= 56 and count <= 512, "unbounded ELF program header table")
        interpreter, dynamic = None, False
        for i in range(count):
            stream.seek(offset + i * size)
            segment = stream.read(56)
            require(len(segment) == 56, "truncated tool ELF header")
            kind = struct.unpack_from("<I", segment)[0]
            dynamic |= kind == 2
            if kind == 3:
                location, length = struct.unpack_from("<Q", segment, 8)[0], struct.unpack_from("<Q", segment, 32)[0]
                require(0 < length <= 4096, "invalid tool ELF interpreter")
                stream.seek(location)
                interpreter = stream.read(length).rstrip(b"\0").decode(errors="replace")
        facts.update(interpreter=interpreter, linkage="dynamic" if interpreter else "static" if not dynamic else "no PT_INTERP; self-relocating/static PIE or self-loader")
    return facts


def write_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".new")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def child_environment(home=None):
    # Preserve the selected runtime's acceleration. Never inherit Git routing
    # or user Python startup settings into a disposable benchmark repository.
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("GIT_", "PYTHON"))}
    env.update(LC_ALL="C", TZ="UTC", PYTHONDONTWRITEBYTECODE="1",
               GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null",
               GIT_TERMINAL_PROMPT="0", GIT_OPTIONAL_LOCKS="0")
    if home is not None:
        env.update(HOME=str(home), XDG_CONFIG_HOME=str(Path(home) / "config"),
                   XDG_CACHE_HOME=str(Path(home) / "cache"))
        # Version/help probes and local tools must not discover the invoking
        # operator's CLI configuration merely through a nonstandard home var.
        for key in ("CODEX_HOME", "CLAUDE_CONFIG_DIR", "OPENAI_API_KEY", "ANTHROPIC_API_KEY"):
            env.pop(key, None)
    return env


def parse_counters(text):
    """Retain individual process reports; aggregate only additive PRoot counts."""
    reports, totals = [], {}
    for line in text.splitlines():
        if "proot-perf:" in line:
            values = {key: int(value) for key, value in
                      re.findall(r"([A-Za-z_][A-Za-z_0-9]*)=(\d+)", line.split("proot-perf:", 1)[1])}
            if values:
                reports.append({"kind": "proot-perf", "values": values})
                for key, value in values.items():
                    totals[key] = totals.get(key, 0) + value
        elif line.lstrip().startswith("{"):
            try:
                value = json.loads(line)
            except ValueError:
                continue
            if isinstance(value, dict) and "faststat" in value:
                reports.append({"kind": "faststat", "values": value["faststat"]})
    return {"reports": reports, "proot_totals": totals,
            "scope": "whole launched process, including preparation/verification; not timed phases alone"}


def process_identity(pid):
    try:
        # comm may contain spaces and ')'; fields after its final ')' are fixed.
        rest = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return {"pid": int(pid), "ppid": int(rest[1]), "state": rest[0], "start": rest[19]}
    except (OSError, ValueError, IndexError):
        return None


def descendants(pid):
    observed = {}
    try:
        names = Path("/proc").iterdir()
        for path in names:
            if path.name.isdigit():
                value = process_identity(int(path.name))
                if value:
                    observed[value["pid"]] = value
    except OSError:
        return []
    found, pending = [], [pid]
    while pending:
        parent = pending.pop()
        children = [value for value in observed.values() if value["ppid"] == parent]
        found.extend(children)
        pending.extend(value["pid"] for value in children)
    return found


def stop_process(process):
    # A timed-out tracer may have tracees with their own process groups. Kill
    # only descendants observed under this invocation, checking PID identity.
    children = descendants(process.pid)
    for value in reversed(children):
        current = process_identity(value["pid"])
        if current and current["start"] == value["start"]:
            try:
                os.kill(value["pid"], signal.SIGKILL)
            except ProcessLookupError:
                pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def execute(argv, cwd, env=None, timeout=120, stdout_path=None, stderr_path=None):
    """Measure real process completion, first bytes and wait4 resource usage.

    Output is streamed to files, not buffered without a bound in Python memory.
    CPU/RSS cover this launched process and kernel-accounted waited descendants;
    they do not separate guest CPU from tracer CPU.
    """
    cwd = Path(cwd)
    stdout_path = Path(stdout_path or cwd / "command.stdout")
    stderr_path = Path(stderr_path or cwd / "command.stderr")
    start = time.monotonic_ns()
    process = subprocess.Popen([str(value) for value in argv], cwd=cwd, env=env,
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, start_new_session=True)
    first_output = None
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ, "stdout")
    selector.register(process.stderr, selectors.EVENT_READ, "stderr")
    sizes = {"stdout": 0, "stderr": 0}
    timed_out = False
    usage = None
    deadline = time.monotonic() + timeout
    with stdout_path.open("wb") as out, stderr_path.open("wb") as err:
        streams = {"stdout": out, "stderr": err}
        try:
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    timed_out = True
                    stop_process(process)
                    break
                for key, _ in selector.select(min(remaining, 0.1)):
                    data = os.read(key.fileobj.fileno(), 65536)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    if first_output is None and key.data == "stdout":
                        first_output = (time.monotonic_ns() - start) / 1e6
                    sizes[key.data] += len(data)
                    streams[key.data].write(data)
                    if sizes[key.data] > 64 * 1024 * 1024:
                        timed_out = True
                        stop_process(process)
                        raise CheckFailure("command output exceeded the 64 MiB artifact limit")
            if not timed_out:
                while True:
                    pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                    if pid:
                        process.returncode = os.waitstatus_to_exitcode(status)
                        break
                    if time.monotonic() >= deadline:
                        timed_out = True
                        usage = None
                        stop_process(process)
                        break
                    time.sleep(0.01)
        finally:
            selector.close()
            process.stdout.close()
            process.stderr.close()
            if process.returncode is None:
                stop_process(process)
    elapsed = (time.monotonic_ns() - start) / 1e6
    result = {"argv": [str(value) for value in argv], "exit_code": process.returncode,
              "timed_out": timed_out, "wall_ms": elapsed,
              "first_stdout_ms": first_output, "stdout_bytes": sizes["stdout"],
              "stderr_bytes": sizes["stderr"], "stdout_sha256": file_digest(stdout_path),
              "stderr_sha256": file_digest(stderr_path),
              "stdout_file": str(stdout_path), "stderr_file": str(stderr_path),
              "cpu_scope": "launched process plus waited descendants, guest/tracer not separated",
              "user_cpu_ms": usage.ru_utime * 1000 if usage else None,
              "system_cpu_ms": usage.ru_stime * 1000 if usage else None,
              "peak_rss_kib": usage.ru_maxrss if usage else None,
              "voluntary_context_switches": usage.ru_nvcsw if usage else None,
              "involuntary_context_switches": usage.ru_nivcsw if usage else None}
    return result


def check_exit(result, expected=(0,)):
    require(not result["timed_out"], f"command timed out: {result['argv'][0]}")
    require(result["exit_code"] in expected,
            f"exit {result['exit_code']} from {result['argv'][0]} (expected {expected}); see stderr artifact")


def distribution(values):
    values = sorted(values)
    if not values:
        return None
    middle = len(values) // 2
    median = values[middle] if len(values) % 2 else (values[middle - 1] + values[middle]) / 2
    return {"count": len(values), "median_ms": median,
            "p95_ms": values[max(0, math.ceil(len(values) * 0.95) - 1)],
            "p95_method": "nearest rank; exploratory tail estimate for small samples",
            "minimum_ms": values[0], "maximum_ms": values[-1]}
