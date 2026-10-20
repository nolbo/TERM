#!/usr/bin/env python3
"""Fixed, guest-only update operations. Originals are retained by the host transaction."""
import contextlib
import filecmp
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

OLD = Path("/run/term-maintenance/old")
LOWER = Path("/run/term-maintenance/lower")


def run(*args):
    subprocess.run(args, check=True, env={**os.environ, "DEBIAN_FRONTEND": "noninteractive", "SYSTEMD_OFFLINE": "1"})


def same(left, right):
    if left.is_symlink() or right.is_symlink():
        return left.is_symlink() and right.is_symlink() and os.readlink(left) == os.readlink(right)
    if not left.exists() or not right.exists():
        return not left.exists() and not right.exists()
    return left.is_file() and right.is_file() and filecmp.cmp(left, right, shallow=False)


def copy_entry(source, target):
    # Never follow a target symlink while copying privileged configuration.
    parent = target.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise RuntimeError(f"Migration target has symlink parent: {target}")
        parent = parent.parent
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink() or target.is_file():
        target.unlink()
    if source.is_symlink():
        target.symlink_to(os.readlink(source))
        shutil.copystat(source, target, follow_symlinks=False)
    elif source.is_file():
        shutil.copy2(source, target)
        os.chown(target, source.stat().st_uid, source.stat().st_gid)
    else:
        raise RuntimeError(f"Unsupported migration entry: {source}")


def merge_config(old, lower, target, managed):
    """Three-way merge: preserve user changes only if the new base agrees, otherwise abort."""
    paths = {p.relative_to(old) for p in old.rglob("*")} | {p.relative_to(lower) for p in lower.rglob("*")}
    for relative in sorted(paths):
        before, source, destination = lower / relative, old / relative, target / relative
        name = "/etc/" + str(relative)
        if name in managed or str(relative) in ("machine-id", "fstab", "resolv.conf"):
            continue
        if source.is_dir() and not source.is_symlink():
            continue
        if same(source, before) or same(source, destination):
            continue
        if not same(before, destination):
            raise RuntimeError(f"Configuration conflict; old environment retained: {name}")
        if source.exists() or source.is_symlink():
            copy_entry(source, destination)
        elif destination.exists() or destination.is_symlink():
            destination.unlink()


def user_packages(old, lower, distribution):
    command = ["apt-mark", "showmanual"] if distribution in ("debian", "ubuntu") else ["pacman", "-Qqe"]
    packages = set(subprocess.check_output(["chroot", str(old), *command], text=True).splitlines())
    baseline = lower / "usr/share/term/base-packages"
    if baseline.is_file():
        packages -= set(baseline.read_text().splitlines())
    else:
        # Legacy images did not record manual selections; subtract their installed base packages.
        query = ["dpkg-query", "-W", "-f=${binary:Package}\\n"] if distribution in ("debian", "ubuntu") else ["pacman", "-Qq"]
        # dpkg accepts an alternate database; pacman uses its fixed local DB path.
        if distribution in ("debian", "ubuntu"):
            query.insert(1, "--admindir=" + str(lower / "var/lib/dpkg"))
        else:
            query[1:1] = ["--dbpath", str(lower / "var/lib/pacman")]
        packages -= set(subprocess.check_output(query, text=True).splitlines())
    packages.discard("term-guest")
    if len(packages) > 4096 or any(not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9+_.:@-]{0,127}", p) for p in packages):
        raise RuntimeError("Invalid package selection")
    return sorted(packages)


@contextlib.contextmanager
def offline_packages(root):
    # Capture live DNS before temporarily replacing it (root can be the running candidate itself).
    dns = Path("/etc/resolv.conf").read_bytes()
    policy = root / "usr/sbin/policy-rc.d"
    resolver = root / "etc/resolv.conf"
    saved = []
    for path in (policy, resolver):
        backup = path.with_name(path.name + ".term-maintenance-backup")
        if backup.exists() or backup.is_symlink():
            raise RuntimeError("Stale guest maintenance backup")
        existed = path.exists() or path.is_symlink()
        if existed:
            path.rename(backup)
        saved.append((path, backup, existed))
    try:
        policy.write_text("#!/bin/sh\nexit 101\n")
        policy.chmod(0o755)
        resolver.write_bytes(dns)
        yield
    finally:
        for path, backup, existed in reversed(saved):
            if path.exists() or path.is_symlink():
                path.unlink()
            if existed:
                backup.rename(path)


def main(operation):
    distribution = Path("/etc/droid/distribution").read_text().strip()
    if (OLD / "etc/droid/distribution").exists():
        assert (OLD / "etc/droid/distribution").read_text().strip() == distribution
    if operation == "components":
        with offline_packages(OLD):
            if distribution in ("debian", "ubuntu"):
                shutil.copy2("/usr/share/term/term-guest.deb", OLD / "tmp/term-guest.deb")
                run("chroot", str(OLD), "apt-get", "update", "-o", "APT::Update::Error-Mode=any")
                # Content-addressed versions are not chronological; install the exact approved local package.
                run("chroot", str(OLD), "apt-get", "install", "-y", "--no-remove", "--reinstall", "--allow-downgrades", "/tmp/term-guest.deb")
            else:
                shutil.copy2("/usr/share/term/term-guest.pkg.tar", OLD / "tmp/term-guest.pkg.tar")
                run("chroot", str(OLD), "pacman", "-Syu", "--noconfirm", "--needed", "python", "sshfs", "fuse3", "sudo")
                # The local package is authenticated by the APK-pinned system image, not a remote repository.
                overwrite = ",".join(path.lstrip("/") for path in Path("/usr/share/term/managed-files").read_text().splitlines())
                run("chroot", str(OLD), "pacman", "-U", "--noconfirm", "--overwrite", overwrite, "/tmp/term-guest.pkg.tar")
        enabled = OLD / "etc/systemd/system/multi-user.target.wants"
        enabled.mkdir(parents=True, exist_ok=True)
        for name in ("droid-sessiond", "droid-agent", "droid-port-agent", "droid-apt-init", "droid-file-transfer",
                     "term-shares", "term-share-broker", "term-android-storage"):
            target = enabled / (name + ".service")
            if not target.exists() and not target.is_symlink():
                target.symlink_to("/etc/systemd/system/" + name + ".service")
        fuse = OLD / "etc/fuse.conf"
        text = fuse.read_text() if fuse.exists() else ""
        if "user_allow_other" not in text.splitlines():
            fuse.write_text(text + "\nuser_allow_other\n")
        run("chroot", str(OLD), "/usr/local/lib/droid/droid-sessiond", "--self-check")
    elif operation == "rebase":
        packages = user_packages(OLD, LOWER, distribution)
        with offline_packages(Path("/")):
            if distribution in ("debian", "ubuntu"):
                run("apt-get", "update", "-o", "APT::Update::Error-Mode=any")
                if packages:
                    run("apt-get", "install", "-y", "--no-remove", *packages)
            else:
                run("pacman", "-Syu", "--noconfirm", "--needed", *packages)
        managed = set(Path("/usr/share/term/managed-files").read_text().splitlines())
        merge_config(OLD / "etc", LOWER / "etc", Path("/etc"), managed)
        # Preserve file/link/mode/owner/xattr/hardlink semantics without traversing other mounts.
        for name in ("home", "root", "srv", "opt", "workspace"):
            if (OLD / name).is_dir() and not (OLD / name).is_symlink():
                run("cp", "-a", "--one-file-system", str(OLD / name) + "/.", "/" + name)
        Path("/var/lib/droid").mkdir(parents=True, exist_ok=True)
        Path("/var/lib/droid/migrated-packages").write_text("\n".join(packages) + "\n")
    else:
        raise RuntimeError("Unsupported maintenance operation")


if __name__ == "__main__":
    main(sys.argv[1])
