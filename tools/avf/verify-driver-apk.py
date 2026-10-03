#!/usr/bin/env python3
"""Ensure the AVF driver is packaged, but compile-only platform APIs are not."""
import argparse
import struct
import zipfile


def classes(dex):
    if not dex.startswith(b'dex\n'):
        raise ValueError('Expected standard DEX')
    strings_count, strings_offset = struct.unpack_from('<II', dex, 56)
    types_count, types_offset = struct.unpack_from('<II', dex, 64)
    classes_count, classes_offset = struct.unpack_from('<II', dex, 96)
    strings = []
    for index in range(strings_count):
        offset, = struct.unpack_from('<I', dex, strings_offset + index * 4)
        while dex[offset] & 0x80:
            offset += 1
        offset += 1
        strings.append(dex[offset:dex.index(b'\x00', offset)])
    types = [strings[struct.unpack_from('<I', dex, types_offset + index * 4)[0]]
             for index in range(types_count)]
    return {types[struct.unpack_from('<I', dex, classes_offset + index * 32)[0]].decode('ascii')
            for index in range(classes_count)}


def verify(path):
    with zipfile.ZipFile(path) as apk:
        definitions = set().union(*(classes(apk.read(name)) for name in apk.namelist()
                                    if name.startswith('classes') and name.endswith('.dex')))
    required = {
        'Ldev/terminal/runtime/avf/system/AospAvfPlatformDriver;',
        'Ldev/terminal/runtime/avf/system/AospAvfRuntime;',
        'Ldev/terminal/app/TerminalApplication;',
    }
    missing = required - definitions
    unexpected = {name for name in definitions if name.startswith('Landroid/system/virtualmachine/')}
    if missing or unexpected:
        raise ValueError(f'Missing driver classes: {sorted(missing)}; packaged platform API classes: {sorted(unexpected)}')
    print('PASS: AVF driver present; platform API declarations absent from APK DEX')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    verify(parser.parse_args().apk)
