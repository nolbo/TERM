#!/usr/bin/env python3
"""Package the factory's TERM-owned files; the sealed system image authenticates this payload."""
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile


def build(root, distribution):
    root = Path(root)
    files = set()
    for pattern in ("usr/local/lib/droid/*", "usr/local/bin/droid", "usr/local/bin/termux-*",
                    "etc/systemd/system/droid*.service", "etc/systemd/system/term-*.service",
                    "etc/systemd/system/droid*.service.d/*", "etc/droid/distribution",
                    "etc/sudoers.d/90-term-droid", "etc/systemd/network/10-term-virtio.network"):
        files.update(p for p in root.glob(pattern) if p.is_file() and not p.is_symlink())
    assert files, "No guest components"
    digest = hashlib.sha256()
    for file in sorted(files):
        digest.update(str(file.relative_to(root)).encode())
        digest.update(file.read_bytes())
    version = "1.0." + digest.hexdigest()[:16]
    output = root / "usr/share/term"
    output.mkdir(parents=True, exist_ok=True)
    (output / "managed-files").write_text("".join("/" + str(p.relative_to(root)) + "\n" for p in sorted(files)))
    with tempfile.TemporaryDirectory() as temporary:
        staging = Path(temporary)
        for source in files:
            target = staging / source.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        if distribution in ("debian", "ubuntu"):
            control = staging / "DEBIAN"
            control.mkdir()
            (control / "control").write_text(
                f"Package: term-guest\nVersion: {version}\nArchitecture: arm64\n"
                "Maintainer: TERM\nDepends: python3, sshfs, fuse3, sudo, util-linux\n"
                "Description: TERM AVF guest services and tools\n")
            subprocess.run(["dpkg-deb", "--root-owner-group", "--build", str(staging), str(output / "term-guest.deb")], check=True)
        else:
            info = (f"pkgname = term-guest\npkgver = {version}-1\npkgdesc = TERM AVF guest services\n"
                    "arch = aarch64\nlicense = GPL-3.0-only\ndepend = python\ndepend = sshfs\ndepend = fuse3\ndepend = sudo\n")
            with tarfile.open(output / "term-guest.pkg.tar", "w") as archive:
                entry = tarfile.TarInfo(".PKGINFO")
                data = info.encode()
                entry.size = len(data)
                entry.mode = 0o644
                archive.addfile(entry, io.BytesIO(data))
                for file in sorted(staging.rglob("*")):
                    archive.add(file, arcname=str(file.relative_to(staging)), recursive=False)
    # Capture the factory's baseline, so full transitions replay user selections only.
    command = ["apt-mark", "showmanual"] if distribution in ("debian", "ubuntu") else ["pacman", "-Qqe"]
    (output / "base-packages").write_bytes(subprocess.check_output(command))


if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2])
