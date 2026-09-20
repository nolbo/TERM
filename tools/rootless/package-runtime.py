#!/usr/bin/env python3
"""Create deterministic local runtime/source release inputs; does not publish."""
import argparse
import gzip
import hashlib
import io
from pathlib import Path
import tarfile


def write_archive(path, entries):
    with path.open('wb') as output:
        with gzip.GzipFile(fileobj=output, mode='wb', filename='', mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w') as archive:
                for name, data, mode in sorted(entries):
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    info.mode = mode
                    info.mtime = 0
                    archive.addfile(info, io.BytesIO(data))
    print(hashlib.sha256(path.read_bytes()).hexdigest(), path.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    args.output.mkdir(parents=True, exist_ok=True)
    write_archive(args.output / 'rootless-runtime-arm64.tar.gz',
                  [(name, (args.payload / name).read_bytes(), 0o755)
                   for name in ('proot', 'proot-loader', 'libproot-faststat.so')])
    entries = []
    # Build inputs and regression sources only. Exclude the downloader containing
    # this archive's digest to avoid a self-referential archive hash.
    for path in sorted((root / 'tools/rootless').rglob('*')):
        if (path.is_file() and not path.is_symlink()
                and path.suffix in {'.c', '.h', '.patch', '.sh', '.py', '.md', '.json'}
                and path.name != 'prepare-release-runtime.py'
                and '__pycache__' not in path.parts):
            entries.append((str(path.relative_to(root)), path.read_bytes(), 0o755 if path.suffix == '.sh' else 0o644))
    for name in ('proot-GPL-2.0-or-later.txt', 'talloc-GPL-3.0.txt', 'libandroid-shmem-BSD-3-Clause.txt'):
        path = root / 'android-app/src/main/assets/licenses' / name
        entries.append((str(path.relative_to(root)), path.read_bytes(), 0o644))
    instructions = '''# Emulated-link accelerated runtime build inputs

Ubuntu aarch64, native Clang 18.1.3, Android NDK r28 sysroot, glibc host compiler.
The build script downloads upstream inputs with pinned versions and SHA-256.

PROOT_BUILD_FASTSTAT=1 PROOT_BUILD_RENAME_LOCAL=1 bash tools/rootless/build-proot-arm64.sh /tmp/runtime
PROOT_BUILD_STDIO=1 bash tools/rootless/faststat/build.sh /tmp/runtime

No device identifiers, user rootfs or credentials are included.
prepare-release-runtime.py is excluded because it pins this archive's hash.
This bundle is generated from the working source tree, not an assumed Git tag.
'''
    entries.append(('BUILD.md', instructions.encode(), 0o644))
    write_archive(args.output / 'rootless-runtime-source.tar.gz', entries)


if __name__ == '__main__':
    main()
