#!/usr/bin/env python3
"""Prepare disposable ARM64 Linux test assets from the existing host. No downloads."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess


def prepare(output):
    output.mkdir(parents=True, exist_ok=True)
    mapping = {
        "bin/dash": Path("/usr/bin/dash"),
        "bin/true": Path("/usr/bin/true"),
        "bin/printf": Path("/usr/bin/printf"),
        "bin/env": Path("/usr/bin/env"),
        "lib/ld-linux-aarch64.so.1": Path("/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
        "lib/aarch64-linux-gnu/libc.so.6": Path("/usr/lib/aarch64-linux-gnu/libc.so.6"),
    }
    for destination, source in mapping.items():
        target = output / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(0o755)
    source = Path(__file__).with_name("guest-test.c")
    for variant in ("static", "dynamic"):
        target = output / "bin" / f"guest-{variant}"
        subprocess.run(["clang", "-O2", "-Wall", "-Wextra", "-Werror",
                        "-Wl,-z,noexecstack,-z,max-page-size=65536",
                        *(["-static"] if variant == "static" else []), str(source), "-o", str(target)], check=True)
    for variant in ("static", "dynamic"):
        target = output / "bin" / f"process-{variant}"
        subprocess.run(["clang", "-O2", "-Wall", "-Wextra", "-Werror",
                        "-Wl,-z,noexecstack,-z,max-page-size=65536",
                        *(["-static"] if variant == "static" else []),
                        str(Path(__file__).with_name("process-test.c")), "-o", str(target)], check=True)
    files = []
    for path in sorted(output.glob("bin/*")) + sorted(output.glob("lib/**/*")):
        if path.is_file() and path.name != "manifest.json":
            data = path.read_bytes()
            if data[:6] != b"\x7fELF\x02\x01" or data[18:20] != b"\xb7\x00":
                raise ValueError(f"Not an ARM64 Linux ELF: {path.name}")
            files.append({"path": str(path.relative_to(output)), "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    # Corrupt real ELF metadata, retaining valid payload bytes; the loader must
    # reject these rather than jump to the otherwise valid program entry.
    original = (output / "bin/true").read_bytes()
    phoff = struct.unpack_from("<Q", original, 32)[0]
    phsize, phcount = struct.unpack_from("<HH", original, 54)
    headers = [phoff + phsize * i for i in range(phcount)]
    loads = [p for p in headers if struct.unpack_from("<I", original, p)[0] == 1]
    interp = next(p for p in headers if struct.unpack_from("<I", original, p)[0] == 3)
    interp_offset = struct.unpack_from("<Q", original, interp + 8)[0]
    interp_size = struct.unpack_from("<Q", original, interp + 32)[0]
    rejected = []
    mutations = {
        "wrong-machine": (18, "H", 62),
        "wrong-class": (4, "B", 1),
        "phoff-overflow": (32, "Q", 2**64 - 8),
        "phnum-overflow": (56, "H", 65535),
        "phentsize": (54, "H", 1),
        "entry-outside": (24, "Q", 2**48),
        "load-offset": (loads[0] + 8, "Q", 2**63),
        "load-filesz": (loads[0] + 32, "Q", 2**63),
        "load-zero-memory": (loads[0] + 40, "Q", 0),
        "load-rwx": (loads[0] + 4, "I", 7),
        "load-alignment": (loads[0] + 48, "Q", 3),
        "interp-unterminated": (interp_offset + interp_size - 1, "B", 65),
    }
    for name, (offset, fmt, value) in mutations.items():
        data = bytearray(original)
        struct.pack_into("<" + fmt, data, offset, value)
        target = output / "bad" / name
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(data); target.chmod(0o755)
        relative = str(target.relative_to(output))
        rejected.append("/" + relative)
        files.append({"path": relative, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    versions = subprocess.check_output(["dpkg-query", "-W", "-f=${Package} ${Version}\n", "dash", "coreutils", "libc6:arm64"], text=True)
    (output / "manifest.json").write_text(json.dumps({"schema": 1, "source": "Existing Ubuntu ARM64 host packages; local test fixture only",
        "package_versions": versions.splitlines(), "files": files, "rejected_elfs": rejected}, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    prepare(parser.parse_args().output)
