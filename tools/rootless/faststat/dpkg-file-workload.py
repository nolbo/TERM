#!/usr/bin/env python3
"""Offline dpkg phases in a disposable root; preserves normal sync behavior."""
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time

COUNT = 1000
CONTENT = b"/* deterministic package workload */\n" * 32


def tar_bytes(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        for name, data in entries:
            info = tarfile.TarInfo(name)
            info.mtime = 0
            info.mode = 0o644
            info.size = len(data)
            if name.endswith("/"):
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
            archive.addfile(info, io.BytesIO(data))
    return output.getvalue()


with tempfile.TemporaryDirectory(prefix="dpkg-cost-") as temporary:
    root = Path(temporary)
    target = root / "target"
    admin = target / "var/lib/dpkg"
    (admin / "updates").mkdir(parents=True)
    (admin / "status").write_text("")
    package = root / "fixture.deb"
    control = b"Package: terminal-file-cost\nVersion: 1\nArchitecture: all\nMaintainer: Test <test@example.invalid>\nDescription: disposable offline file workload\n"
    members = [("debian-binary", b"2.0\n"),
               ("control.tar.gz", tar_bytes([("./control", control)])),
               ("data.tar.gz", tar_bytes([(name, b"") for name in
                                          ("./usr/", "./usr/include/", "./usr/include/terminal-file-cost/")] +
                                         [(f"./usr/include/terminal-file-cost/{n:04}.h", CONTENT)
                                           for n in range(COUNT)]))]
    with package.open("wb") as output:
        output.write(b"!<arch>\n")
        for name, data in members:
            output.write(f'{name + "/":<16}{0:<12}{0:<6}{0:<6}{100644:<8}{len(data):<10}`\n'.encode())
            output.write(data)
            if len(data) % 2:
                output.write(b"\n")
    env = dict(os.environ, PATH="/usr/sbin:/usr/bin:/sbin:/bin")
    base = ["dpkg", "--root=" + str(target), "--log=" + str(root / "dpkg.log")]
    results = {"files": COUNT, "bytes_per_file": len(CONTENT), "phases_ms": {}}
    for phase, args in (("unpack", ["--unpack", str(package)]),
                        ("configure", ["--configure", "terminal-file-cost"])):
        start = time.monotonic_ns()
        subprocess.run(base + args, check=True, env=env, stdout=subprocess.DEVNULL)
        results["phases_ms"][phase] = (time.monotonic_ns() - start) / 1e6
    files = list((target / "usr/include/terminal-file-cost").glob("*.h"))
    assert len(files) == COUNT and all(path.read_bytes() == CONTENT for path in files)
    status = (admin / "status").read_text()
    assert "Status: install ok installed" in status
    print(json.dumps(results))
