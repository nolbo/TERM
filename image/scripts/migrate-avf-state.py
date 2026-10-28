#!/usr/bin/env python3
"""Fixed, guest-only update operations. Originals are retained by the host transaction."""
import contextlib
import filecmp
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile

OLD = Path("/run/term-maintenance/old")
LOWER = Path("/run/term-maintenance/lower")
STATE = Path("/run/term-maintenance/state")
ACCOUNT_FIELDS = {"passwd": 7, "group": 4, "shadow": 9, "gshadow": 4}
ACCOUNT_LOCAL_FILES = set(ACCOUNT_FIELDS) | {name + "-" for name in ACCOUNT_FIELDS} | {".pwd.lock"}


def run(*args):
    subprocess.run(args, check=True, env={**os.environ, "DEBIAN_FRONTEND": "noninteractive", "SYSTEMD_OFFLINE": "1"})


def same(left, right):
    if left.is_symlink() or right.is_symlink():
        return left.is_symlink() and right.is_symlink() and os.readlink(left) == os.readlink(right)
    if not left.exists() or not right.exists():
        return not left.exists() and not right.exists()
    return left.is_file() and right.is_file() and filecmp.cmp(left, right, shallow=False)


def reject_symlink_parents(path):
    parent = path.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise RuntimeError(f"Migration path has symlink parent: {path}")
        parent = parent.parent


def copy_entry(source, target):
    # Never follow a target symlink while copying privileged configuration.
    reject_symlink_parents(target)
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


def account_conflict(database, name, reason):
    # Do not include record contents: shadow/gshadow can contain password hashes.
    raise RuntimeError(f"Account configuration conflict; old environment retained: /etc/{database} ({name}: {reason})")


def read_accounts(directory, database):
    path = directory / database
    reject_symlink_parents(path)
    if path.is_symlink() or (path.exists() and not path.is_file()):
        account_conflict(database, "file", "expected a regular file")
    records = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if not line:
                continue
            fields = tuple(line.split(":"))
            name = fields[0]
            if (len(fields) != ACCOUNT_FIELDS[database] or not name or
                    name.startswith(("+", "-")) or name in records):
                account_conflict(database, "record", "invalid or duplicate account entry")
            if database in ("passwd", "group"):
                indexes = (2, 3) if database == "passwd" else (2,)
                if any(not re.fullmatch(r"[0-9]+", fields[i]) or int(fields[i]) >= 2**32 - 1 for i in indexes):
                    account_conflict(database, name, "invalid numeric identity")
            records[name] = fields
    return records


def merge_account_record(database, name, before, source, destination):
    if source == before:
        return destination
    if source == destination or before == destination:
        return source
    if source is None or destination is None:
        account_conflict(database, name, "deletion conflicts with an edited account")
    merged = [name]
    for index in range(1, ACCOUNT_FIELDS[database]):
        base = before[index] if before is not None else None
        old, new = source[index], destination[index]
        if (database == "group" and index == 3) or (database == "gshadow" and index in (2, 3)):
            # Three-way set edits preserve removals as well as independent additions.
            members = lambda value: set(filter(None, (value or "").split(",")))
            value = (members(new) - (members(base) - members(old))) | (members(old) - members(base))
            merged.append(",".join(sorted(value)))
        elif old == base:
            merged.append(new)
        elif old == new or new == base:
            merged.append(old)
        else:
            account_conflict(database, name, f"both versions changed field {index + 1}")
    return tuple(merged)


def validate_accounts(merged, old, target):
    # Both copied user data and new package files retain numeric ownership. Never
    # silently remap either side, including IDs belonging to deleted accounts.
    for database in ("passwd", "group"):
        owners = {}
        for name, fields in merged[database].items():
            identity = int(fields[2])
            if identity in owners:
                account_conflict(database, name, "duplicate numeric identity")
            owners[identity] = name
        for records in (old[database], target[database]):
            for name, fields in records.items():
                identity = int(fields[2])
                if identity in owners and owners[identity] != name:
                    account_conflict(database, name, "numeric identity would belong to another account")
                if name in merged[database] and int(merged[database][name][2]) != identity:
                    account_conflict(database, name, "numeric identity differs between environments")
    gids = {int(fields[2]) for fields in merged["group"].values()}
    for name, fields in merged["passwd"].items():
        if int(fields[3]) not in gids:
            account_conflict("passwd", name, "primary group is missing")
        if fields[1] == "x" and name not in merged["shadow"]:
            account_conflict("shadow", name, "password entry is missing")
    for name in merged["shadow"]:
        if name not in merged["passwd"]:
            account_conflict("shadow", name, "user is missing")
    for name, fields in merged["gshadow"].items():
        group = merged["group"].get(name)
        if group is None or set(filter(None, group[3].split(","))) != set(filter(None, fields[3].split(","))):
            account_conflict("gshadow", name, "group membership is inconsistent")
    if merged["gshadow"]:
        for name in merged["group"]:
            if name not in merged["gshadow"]:
                account_conflict("gshadow", name, "group entry is missing")


def merge_accounts(old, lower, target):
    versions = [{name: read_accounts(directory, name) for name in ACCOUNT_FIELDS}
                for directory in (old, lower, target)]
    source, before, destination = versions
    merged = {}
    for database in ACCOUNT_FIELDS:
        merged[database] = {}
        # Keep the candidate's order and append retained user additions.
        names = dict.fromkeys([*destination[database], *source[database], *before[database]])
        for name in names:
            record = merge_account_record(database, name, before[database].get(name),
                                          source[database].get(name), destination[database].get(name))
            if record is not None:
                merged[database][name] = record
    validate_accounts(merged, source, destination)
    staged = []
    try:
        for database, records in merged.items():
            if records == destination[database]:
                continue
            path = target / database
            template = path if path.exists() else old / database
            # Create private files first; retain the candidate's owner/mode/xattrs,
            # especially shadow/gshadow permissions, before atomic replacement.
            fd, temporary = tempfile.mkstemp(prefix=f".{database}.term-", dir=target)
            temporary = Path(temporary)
            staged.append((temporary, path))
            with os.fdopen(fd, "w") as output:
                output.write("".join(":".join(fields) + "\n" for fields in records.values()))
            metadata = template.stat()
            os.chown(temporary, metadata.st_uid, metadata.st_gid)
            shutil.copystat(template, temporary)
            temporary.chmod(stat.S_IMODE(metadata.st_mode))
        for temporary, path in staged:
            temporary.replace(path)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)


def merge_config(old, lower, target, managed):
    """Three-way merge: preserve user changes only if the new base agrees, otherwise abort."""
    merge_accounts(old, lower, target)
    paths = {p.relative_to(old) for p in old.rglob("*")} | {p.relative_to(lower) for p in lower.rglob("*")}
    for relative in sorted(paths):
        before, source, destination = lower / relative, old / relative, target / relative
        name = "/etc/" + str(relative)
        if name in managed or str(relative) in ACCOUNT_LOCAL_FILES | {"machine-id", "fstab", "resolv.conf"}:
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


def copy_data(source, target, excluded, links, relative=Path("."), device=None):
    """Copy quiescent Linux data without following links or crossing filesystems.

    Keep hardlinks across all selected trees, ownership, modes and xattrs (which
    include Linux ACLs/capabilities). Package databases and TERM policy are never
    transplanted into the newly installed userspace.
    """
    if any(relative == name or name in relative.parents for name in excluded):
        return
    metadata = source.lstat()
    if device is None:
        device = metadata.st_dev
    if metadata.st_dev != device or stat.S_ISSOCK(metadata.st_mode):
        return
    reject_symlink_parents(target)
    directory = stat.S_ISDIR(metadata.st_mode)
    if not directory and any(relative in name.parents for name in excluded):
        raise RuntimeError(f"Persistent data would replace managed parent: {source}")
    if target.is_symlink() or (target.exists() and not (directory and target.is_dir())):
        if target.is_dir() and not target.is_symlink():
            shutil.rmtree(target)
        else:
            target.unlink()
    if directory:
        target.mkdir(parents=True, exist_ok=True)
        for entry in sorted(source.iterdir()):
            copy_data(entry, target / entry.name, excluded, links, relative / entry.name, device)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        identity = (metadata.st_dev, metadata.st_ino)
        if stat.S_ISREG(metadata.st_mode) and identity in links:
            os.link(links[identity], target)
            return
        if stat.S_ISLNK(metadata.st_mode):
            target.symlink_to(os.readlink(source))
        elif stat.S_ISREG(metadata.st_mode):
            if metadata.st_blocks * 512 < metadata.st_size:
                run("cp", "--sparse=always", "--", str(source), str(target))
            else:
                shutil.copyfile(source, target)
            links[identity] = target
        elif stat.S_ISFIFO(metadata.st_mode):
            os.mkfifo(target)
        elif stat.S_ISCHR(metadata.st_mode) or stat.S_ISBLK(metadata.st_mode):
            os.mknod(target, metadata.st_mode, metadata.st_rdev)
        else:
            raise RuntimeError(f"Unsupported persistent data entry: {source}")
    os.chown(target, metadata.st_uid, metadata.st_gid, follow_symlinks=False)
    shutil.copystat(source, target, follow_symlinks=False)
    # copystat may silently skip unsupported/denied xattrs. A migration must
    # instead fail, retaining the old generation, rather than lose ACLs/caps.
    attributes = {name: os.getxattr(source, name, follow_symlinks=False)
                  for name in os.listxattr(source, follow_symlinks=False)}
    for name in set(os.listxattr(target, follow_symlinks=False)) - attributes.keys():
        os.removexattr(target, name, follow_symlinks=False)
    for name, value in attributes.items():
        os.setxattr(target, name, value, follow_symlinks=False)


def migrate_data(old, lower, target, state, source_format):
    excluded = {Path(name) for name in (
        "var/lib/dpkg", "var/lib/apt", "var/lib/pacman", "var/lib/ucf",
        "var/lib/droid", "var/lib/systemd", "var/lib/dbus")}
    for root in (old, lower, target):
        listing = root / "usr/share/term/managed-files"
        if listing.is_file():
            excluded.update(Path(name.lstrip("/")) for name in listing.read_text().splitlines())
    links = {}
    for name in ("home", "root", "srv", "opt", "workspace", "usr/local", "var/lib", "var/spool", "var/www"):
        source = old / name
        if source.exists() or source.is_symlink():
            copy_data(source, target / name, excluded, links, Path(name))
    # v1's direct state data is outside its merged root. v2 keeps the compatible
    # path as an ordinary directory on the root ext4 filesystem.
    source = state if source_format == 1 else old / ".droid/state"
    if source.is_symlink():
        raise RuntimeError("Persistent state directory must not be a symlink")
    if source.exists():
        ignored = {Path(name) for name in ("upper", "work", "lost+found")} if source_format == 1 else set()
        for entry in sorted(source.iterdir()):
            copy_data(entry, target / ".droid/state" / entry.name, ignored, links, Path(entry.name))


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


def retire_shared_directories(root):
    def remove(path):
        reject_symlink_parents(path)
        path.unlink(missing_ok=True)

    # Never remove user data or distro FUSE support; retire only TERM's mounts.
    for name in ("term-shares", "term-share-broker", "term-share-selftest", "term-android-storage", "term-session-recovery"):
        remove(root / "etc/systemd/system/multi-user.target.wants" / (name + ".service"))
        remove(root / "etc/systemd/system" / (name + ".service"))
    for name in ("shares.conf", "android-storage.conf"):
        remove(root / "etc/systemd/system/droid-sessiond.service.d" / name)
    for name in ("mount-shared-directories.sh", "mount-android-storage.sh", "private-share-broker.py",
                 "private-share-recovery.py", "image-share-selftest.py", "session-recovery.py", "private-share-transport.py"):
        remove(root / "usr/local/lib/droid" / name)


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
                run("chroot", str(OLD), "pacman", "-Syu", "--noconfirm", "--needed", "python", "sudo")
                # The local package is authenticated by the APK-pinned system image, not a remote repository.
                overwrite = ",".join(path.lstrip("/") for path in Path("/usr/share/term/managed-files").read_text().splitlines())
                run("chroot", str(OLD), "pacman", "-U", "--noconfirm", "--overwrite", overwrite, "/tmp/term-guest.pkg.tar")
        enabled = OLD / "etc/systemd/system/multi-user.target.wants"
        enabled.mkdir(parents=True, exist_ok=True)
        for name in ("droid-sessiond", "droid-agent", "droid-port-agent", "droid-apt-init", "droid-file-transfer"):
            target = enabled / (name + ".service")
            if not target.exists() and not target.is_symlink():
                target.symlink_to("/etc/systemd/system/" + name + ".service")
        retire_shared_directories(OLD)
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
        source_formats = re.findall(r"(?:^|\s)term.source-format=([12])(?=\s|$)", Path("/proc/cmdline").read_text())
        if len(source_formats) != 1:
            raise RuntimeError("Missing or ambiguous source disk format")
        migrate_data(OLD, LOWER, Path("/"), STATE, int(source_formats[0]))
        retire_shared_directories(Path("/"))
        Path("/var/lib/droid").mkdir(parents=True, exist_ok=True)
        Path("/var/lib/droid/migrated-packages").write_text("\n".join(packages) + "\n")
    else:
        raise RuntimeError("Unsupported maintenance operation")


if __name__ == "__main__":
    main(sys.argv[1])
