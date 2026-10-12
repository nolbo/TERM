#!/usr/bin/env python3
"""Prepare distro userspace inside the factory container; never mount it on the host."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import urllib.request
from urllib.parse import urlparse

PROFILES = json.loads(Path(__file__).with_name('avf-distributions.json').read_text())


def prepare(distribution, destination):
    destination.mkdir(parents=True, exist_ok=True)
    if distribution == 'debian':
        return  # Google's Debian root_part remains the legacy base.
    profile = PROFILES[distribution]
    archive = destination / 'source.tar.xz'
    name = f"{profile['archiveRoot']}-pd-{profile['release']}.tar.xz"
    url = f"https://github.com/termux/proot-distro/releases/download/{profile['release']}/{name}"
    checksum, total = hashlib.sha256(), 0
    with urllib.request.urlopen(url, timeout=30) as source, archive.open('xb') as output:
        if urlparse(source.url).scheme != 'https':
            raise ValueError('Rootfs download redirected away from HTTPS')
        while chunk := source.read(1024 * 1024):
            total += len(chunk)
            if total > 256 * 1024 ** 2:
                raise ValueError('Rootfs archive exceeds size limit')
            checksum.update(chunk)
            output.write(chunk)
    if checksum.hexdigest() != profile['sha256']:
        raise ValueError('Rootfs archive checksum mismatch')
    root = destination / 'rootfs'
    root.mkdir()
    # This pinned archive is unpacked only inside an isolated build container.
    subprocess.run(['tar', '-xJf', str(archive), '--strip-components=1', '-C', str(root)], check=True)
    for name, major, minor in [('null', 1, 3), ('zero', 1, 5), ('random', 1, 8), ('urandom', 1, 9)]:
        device = root / 'dev' / name
        device.parent.mkdir(exist_ok=True)
        device.unlink(missing_ok=True)
        os.mknod(device, stat.S_IFCHR | 0o666, os.makedev(major, minor))
    resolver = root / 'etc/resolv.conf'
    resolver.unlink(missing_ok=True)
    shutil.copyfile('/etc/resolv.conf', resolver)
    if distribution == 'ubuntu':
        policy = root / 'usr/sbin/policy-rc.d'
        policy.write_text('#!/bin/sh\nexit 101\n')
        policy.chmod(0o755)
        command = ('export DEBIAN_FRONTEND=noninteractive; apt-get -o APT::Update::Error-Mode=any update; '
                   'apt-get -y --no-install-recommends install systemd systemd-sysv systemd-resolved '
                   'sudo util-linux kmod ca-certificates; apt-get clean')
    else:
        command = ('pacman -Syu --needed --noconfirm systemd systemd-sysvcompat sudo util-linux '
                   'shadow pacman archlinuxarm-keyring libarchive tar gzip ca-certificates; '
                   "sed -i '/^[[:space:]]*DisableSandbox[[:space:]]*$/d' /etc/pacman.conf")
    subprocess.run(['chroot', str(root), '/bin/bash', '-eu', '-c', command], check=True)
    if distribution == 'ubuntu':
        policy.unlink()
    # Build DNS and PRoot-only host mounts are never part of the sealed rootfs.
    resolver.unlink()
    resolver.symlink_to('/run/systemd/resolve/stub-resolv.conf')
    (root / 'etc/hostname').write_text('localhost\n')
    (root / 'etc/machine-id').write_text('')
    subprocess.run(['tar', '--numeric-owner', '-cf', str(destination / 'rootfs.tar'), '-C', str(root), '.'], check=True)
    shutil.rmtree(root)
    archive.unlink()


if __name__ == '__main__':
    prepare(sys.argv[1], Path(sys.argv[2]))
