#!/usr/bin/env python3
"""Check ARM64 PIE/dependency/16 KiB integrity, including the final APK bytes."""
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

TOOLS = ['jq', 'curl', 'ssh', 'ssh-keygen', 'rg', 'vim']
LIBS = ['term-crypto', 'term-ssl']
SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libz.so'}


def inspect(data):
    assert len(data) >= 64 and data[:6] == b'\x7fELF\x02\x01', 'ELF64 little-endian required'
    assert struct.unpack_from('<HH', data, 16) == (3, 183), 'ARM64 ET_DYN (PIE/shared) required'
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    loads = 0
    for i in range(count):
        kind, flags, pos, address, _, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', data, offset + size * i)
        if kind == 1:
            loads += 1
            assert align >= 16384 and (address - pos) % 16384 == 0, '16 KiB LOAD alignment required'
            assert not flags & 1 or not flags & 2, 'Writable executable LOAD rejected'
        if kind == 3:
            assert data[pos:pos + filesz].rstrip(b'\0') == b'/system/bin/linker64', 'Bionic linker64 required'
    assert loads > 0


def main():
    if sys.argv[1] == '--apk':
        with zipfile.ZipFile(sys.argv[2]) as apk:
            manifest = json.loads(apk.read('assets/native-cli/manifest.json'))
            for name, record in manifest['executables'].items():
                data = apk.read('lib/arm64-v8a/' + name)
                inspect(data)
                assert hashlib.sha256(data).hexdigest() == record['sha256'], 'APK payload changed: ' + name
            for name, record in manifest['resources'].items():
                assert hashlib.sha256(apk.read('assets/native-cli/runtime/' + name)).hexdigest() == record['sha256']
            assert not any(re.search(r'/lib(?:git|git2)(?:[.-]|$)', n) for n in apk.namelist()), 'Git payload excluded'
            documents = dict(json.loads(apk.read('assets/licenses/index.json'))['documents'])
            for component in ['openssl', 'ncurses', 'jq', 'curl', 'openssh', 'rg', 'vim']:
                # License filenames differ upstream; require a recorded document
                # per component, rather than assuming one universal filename.
                assert any(p.startswith('licenses/native-cli/' + component + '-') for p in documents), 'Missing license: ' + component
            for path, digest in documents.items():
                if path.startswith('licenses/native-cli/'):
                    body = apk.read('assets/licenses/blobs/' + digest + '.txt')
                    assert hashlib.sha256(body).hexdigest() == digest, 'License integrity: ' + path
            print(json.dumps({'apkBytes': Path(sys.argv[2]).stat().st_size,
                              'tools': {n: {'raw': apk.getinfo('lib/arm64-v8a/lib' + n + '.so').file_size,
                                            'apk': apk.getinfo('lib/arm64-v8a/lib' + n + '.so').compress_size}
                                        for n in TOOLS + LIBS},
                              'runtimeApkBytes': sum(i.compress_size for i in apk.infolist() if i.filename.startswith('assets/native-cli/')),
                              'vimRuntimeApkBytes': sum(i.compress_size for i in apk.infolist() if i.filename.startswith('assets/native-cli/runtime/vim/')),
                              'caBundleApkBytes': apk.getinfo('assets/native-cli/runtime/cacert.crt').compress_size,
                              'licenseDocuments': sum(p.startswith('licenses/native-cli/') for p in documents)}, indent=2))
        return
    jni, assets = map(Path, sys.argv[1:3])
    expected = {'lib' + n + '.so' for n in TOOLS + LIBS}
    assert {p.name for p in jni.iterdir()} == expected, 'Unexpected/missing CLI payload'
    manifest = {'schema': 1, 'abi': 'arm64-v8a', 'api': 31, 'pageAlignment': 16384, 'executables': {}, 'resources': {}}
    for path in sorted(jni.iterdir()):
        data = path.read_bytes()
        inspect(data)
        dynamic = subprocess.check_output(['readelf', '-d', path], text=True)
        needed = re.findall(r'\(NEEDED\).*\[(.*?)\]', dynamic)
        assert set(needed) <= SYSTEM | expected, 'Unpackaged dependency: ' + str(needed)
        manifest['executables'][path.name] = dict(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data), needed=needed)
    for path in sorted((assets / 'runtime').rglob('*')):
        if path.is_file():
            data = path.read_bytes()
            manifest['resources'][path.relative_to(assets / 'runtime').as_posix()] = dict(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
    target = assets / 'manifest.json'
    if '--write-manifest' in sys.argv:
        target.write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':')) + '\n')
    else:
        assert json.loads(target.read_text()) == manifest, 'Manifest mismatch'
    print('Verified ARM64/Bionic, 16 KiB LOAD alignment, dependencies and SHA-256 for', len(expected), 'ELFs')


if __name__ == '__main__':
    main()
