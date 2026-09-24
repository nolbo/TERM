#!/usr/bin/env python3
"""Prepare disposable ARM64 Linux test assets from the existing host. No downloads."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess


def application_files():
    """Existing distro programs/stdlib and their ELF dependencies, not downloads."""
    mapping = {"usr/bin/python3": Path("/usr/bin/python3"), "usr/bin/git": Path("/usr/bin/git")}
    for root in (Path("/usr/lib/python3.12"), Path("/usr/share/git-core/templates")):
        for source in root.rglob("*"):
            if source.is_file() and "__pycache__" not in source.parts and not source.name.endswith(".pyc"):
                mapping[str(source.relative_to("/"))] = source
    pending = list(mapping.values())
    seen = set()
    while pending:
        source = pending.pop()
        if source.resolve() in seen:
            continue
        seen.add(source.resolve())
        with source.open("rb") as stream:
            if stream.read(4) != b"\x7fELF":
                continue
        dynamic = subprocess.check_output(["readelf", "-d", str(source)], text=True)
        for name in re.findall(r"\(NEEDED\).*\[([^]]+)\]", dynamic):
            if "/" in name:
                raise ValueError(f"Unexpected dependency path: {name}")
            dependency = Path("/usr/lib/aarch64-linux-gnu") / name
            if not dependency.is_file():
                raise ValueError(f"Missing existing dependency: {name}")
            destination = f"lib/aarch64-linux-gnu/{name}"
            mapping[destination] = dependency
            pending.append(dependency)
    mapping["applications.py"] = Path(__file__).with_name("application-test.py")
    mapping["network.py"] = Path(__file__).with_name("network-test.py")
    return mapping


def prepare(output, applications=False):
    output.mkdir(parents=True, exist_ok=True)
    mapping = {
        "bin/dash": Path("/usr/bin/dash"),
        "bin/true": Path("/usr/bin/true"),
        "bin/printf": Path("/usr/bin/printf"),
        "bin/env": Path("/usr/bin/env"),
        "lib/ld-linux-aarch64.so.1": Path("/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
        "lib/aarch64-linux-gnu/libc.so.6": Path("/usr/lib/aarch64-linux-gnu/libc.so.6"),
    }
    for name in ("cp", "mv", "mkdir", "rm", "chmod", "touch", "ln", "cat", "stty"):
        mapping[f"bin/{name}"] = Path(f"/usr/bin/{name}")
    for name in ("libselinux.so.1", "libacl.so.1", "libattr.so.1", "libpcre2-8.so.0", "libresolv.so.2"):
        mapping[f"lib/aarch64-linux-gnu/{name}"] = Path(f"/usr/lib/aarch64-linux-gnu/{name}")
    if applications:
        mapping.update(application_files())
    for destination, source in mapping.items():
        target = output / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(0o755 if source.stat().st_mode & 0o111 else 0o644)
    source = Path(__file__).with_name("guest-test.c")
    for variant in ("static", "dynamic"):
        target = output / "bin" / f"guest-{variant}"
        subprocess.run(["clang", "-O2", "-Wall", "-Wextra", "-Werror",
                        "-Wl,-z,noexecstack,-z,max-page-size=65536",
                        *(["-static"] if variant == "static" else []), str(source), "-o", str(target)], check=True)
        mapping[str(target.relative_to(output))] = target
    for program in ("process", "files", "fds", "tty", "network", "dns", "unix"):
        for variant in ("static", "dynamic"):
            target = output / "bin" / f"{program}-{variant}"
            subprocess.run(["clang", "-O2", "-Wall", "-Wextra", "-Werror",
                            "-Wl,-z,noexecstack,-z,max-page-size=65536",
                            *(["-static"] if variant == "static" else []),
                            str(Path(__file__).with_name(f"{program}-test.c")),
                            *(["-lresolv"] if program == "dns" else []), "-o", str(target)], check=True)
            mapping[str(target.relative_to(output))] = target
    if applications:
        # Disposable, freshly generated test credentials. Never commit the key
        # or use it outside these loopback tests. Both engines use these bytes.
        tls = output / "network-fixture"
        tls.mkdir(exist_ok=True)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
                        "-nodes", "-keyout", str(tls / "key.pem"), "-out", str(tls / "cert.pem"),
                        "-days", "2", "-subj", "/CN=fixture.local",
                        "-addext", "subjectAltName=DNS:fixture.local,IP:127.0.0.1"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)
        (tls / "key.pem").chmod(0o600)
        (output / "etc").mkdir(exist_ok=True)
        (output / "etc/hosts").write_text("127.0.0.1 localhost fixture.local wrong.invalid\n::1 ip6-localhost\n")
        (output / "etc/nsswitch.conf").write_text("hosts: files\n")
        for path in (tls / "key.pem", tls / "cert.pem", output / "etc/hosts", output / "etc/nsswitch.conf"):
            mapping[str(path.relative_to(output))] = path
    files = []
    for relative in sorted(mapping):
        path = output / relative
        data = path.read_bytes()
        if data[:4] == b"\x7fELF":
            if data[:6] != b"\x7fELF\x02\x01" or data[18:20] != b"\xb7\x00":
                raise ValueError(f"Not an ARM64 Linux ELF: {path.name}")
        files.append({"path": relative, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                      "executable": bool(path.stat().st_mode & 0o111)})
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
    versions = subprocess.check_output(["dpkg-query", "-W", "-f=${Package} ${Version}\n", "dash", "coreutils", "libc6:arm64",
                                       "libselinux1:arm64", "libacl1:arm64", "libattr1:arm64", "libpcre2-8-0:arm64",
                                       *(["python3.12", "libpython3.12-stdlib:arm64", "git", "openssl"] if applications else [])], text=True)
    (output / "manifest.json").write_text(json.dumps({"schema": 1, "applications": applications,
        "source": "Existing Ubuntu ARM64 host packages; local test fixture only",
        "package_versions": versions.splitlines(), "files": files, "rejected_elfs": rejected}, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--applications", action="store_true")
    args = parser.parse_args()
    prepare(args.output, args.applications)
