#!/usr/bin/env python3
"""Publish the exact pinned upstream sources, local recipe and Cargo dependencies."""
import argparse
import gzip
import hashlib
import io
from pathlib import Path
import tarfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    recipe = repo / 'tools/native-cli'
    files = [(p, p.relative_to(repo).as_posix()) for p in sorted(recipe.iterdir()) if p.is_file()]
    wrapper = repo / 'tools/iphone/android-clang.sh'
    files.append((wrapper, 'tools/iphone/android-clang.sh'))
    for line in (recipe / 'sources.lock').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        name, _, digest, _ = line.split()
        source = args.build / 'downloads' / (name + '.archive')
        if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise ValueError('Source checksum mismatch: ' + name)
        files.append((source, 'native-cli/downloads/' + source.name))
    vendor = args.build / 'rg-vendor'
    if not vendor.is_dir() or not any(vendor.iterdir()):
        raise ValueError('Missing locked Cargo vendor sources')
    files.extend((p, 'native-cli/rg-vendor/' + p.relative_to(vendor).as_posix())
                 for p in sorted(vendor.rglob('*')) if p.is_file())
    readme = b'''TERM Native CLI corresponding sources

The original upstream archives are verified against tools/native-cli/sources.lock.
The build recipe contains all local OpenSSH patches. Cargo dependency sources are
included in native-cli/rg-vendor; upstream Cargo.lock pins the dependency graph.
The checked-in CA bundle and legal notices accompany the recipe.

Use NDK 28.0.13004108, Rust 1.89.0 + aarch64-linux-android, Python 3, make, Perl,
host tic and LLVM utilities. On ARM64 use the documented host clang wrapper.
Copy native-cli/downloads into BUILD/downloads, then run:
  python3 tools/native-cli/build.py BUILD /path/to/android-ndk
For offline Cargo, set a Cargo source replacement for crates-io to the absolute
native-cli/rg-vendor directory in your Cargo configuration.
See docs/NATIVE_SHELL.md in the matching TERM repository revision for details.
'''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix('.tmp')
    epoch = 1781913600
    with temporary.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=epoch) as zipped:
        with tarfile.open(fileobj=zipped, mode='w') as archive:
            info = tarfile.TarInfo('README.native-cli-source.txt')
            info.size, info.mtime, info.mode = len(readme), epoch, 0o644
            archive.addfile(info, io.BytesIO(readme))
            for source, name in files:
                info = tarfile.TarInfo(name)
                info.size, info.mtime = source.stat().st_size, epoch
                info.mode = 0o755 if source.stat().st_mode & 0o111 else 0o644
                with source.open('rb') as body:
                    archive.addfile(info, body)
    temporary.replace(args.output)
    print('Native CLI source bundle:', args.output.stat().st_size, 'bytes')


if __name__ == '__main__':
    main()
