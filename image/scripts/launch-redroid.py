#!/usr/bin/env python3
"""Start CPU-rendered ReDroid inside a TERM Ubuntu container-kernel guest.

This optional guest-root tool is not installed in the base image. It preserves
the image's build properties and disables only eager shader-cache priming.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile


def build_properties(original, prime_cache=False):
    if len(original) > 1024 * 1024 or b'\0' in original:
        raise ValueError('Invalid Android build properties')
    # Keep security/build properties and imports byte-for-byte. Remove duplicate
    # values for this one performance setting before appending its final value.
    lines = [line for line in original.splitlines(keepends=True)
             if not re.match(rb'\s*service\.sf\.prime_shader_cache\s*=', line)]
    prefix = b''.join(lines)
    if prefix and not prefix.endswith(b'\n'):
        prefix += b'\n'
    return prefix + b'service.sf.prime_shader_cache=' + (b'1' if prime_cache else b'0') + b'\n'


def verify_kernel():
    metadata = Path('/usr/share/term/kernel-build.json')
    if not metadata.is_file():
        raise ValueError('Requires the verified TERM container-kernel guest image')
    build = json.loads(metadata.read_text())
    config = gzip.decompress(Path('/proc/config.gz').read_bytes())
    if (hashlib.sha256(config).hexdigest() != build['artifacts']['kernel.config']
            or os.uname().release != build['kernelRelease']):
        raise ValueError('Running kernel differs from the installed image metadata')
    required = ('PID_NS', 'IPC_NS', 'SYSVIPC', 'POSIX_MQUEUE', 'CGROUP_PIDS',
                'NETFILTER_XT_MATCH_ADDRTYPE', 'DMABUF_HEAPS_SYSTEM', 'ANDROID_BINDERFS',
                'MEMFD_CREATE', 'SECCOMP', 'ARM64_4K_PAGES')
    lines = set(config.splitlines())
    if any(('CONFIG_' + name + '=y').encode() not in lines for name in required):
        raise ValueError('Running kernel lacks required container/ReDroid features')
    heap = Path('/dev/dma_heap/system')
    if not heap.exists() or not stat.S_ISCHR(heap.stat().st_mode):
        raise ValueError('System DMA heap device is unavailable')


def image_properties(run, image):
    # Android binaries need the APEX mounts created by init. Read this file from
    # a stopped container instead of trying to execute toybox before Android init.
    container = run(['create', '--pull=never', '--network', 'none', '--cap-drop=ALL',
                     '--security-opt', 'no-new-privileges', image]).stdout.decode().strip()
    if not re.fullmatch('[a-f0-9]{64}', container):
        raise ValueError('Invalid inspection container ID')
    try:
        with tempfile.TemporaryDirectory(prefix='term-redroid-props-') as temp:
            target = Path(temp) / 'build.prop'
            run(['cp', container + ':/system/build.prop', str(target)])
            if target.is_symlink() or not target.is_file() or target.stat().st_size > 1024 * 1024:
                raise ValueError('Invalid Android build properties file')
            return target.read_bytes()
    finally:
        run(['rm', container])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='redroid/redroid:15.0.0-latest')
    parser.add_argument('--name', default='redroid')
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--width', type=int, default=240)
    parser.add_argument('--height', type=int, default=320)
    parser.add_argument('--fps', type=int, default=10)
    parser.add_argument('--dpi', type=int, default=120)
    parser.add_argument('--adb-port', type=int, help='Optional guest-loopback ADB port')
    parser.add_argument('--prime-shader-cache', action='store_true', help='Enable original priming for A/B diagnosis')
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('Run with sudo inside the Ubuntu guest')
    if not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}', args.name):
        parser.error('Invalid container name')
    if not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9._/:@-]*', args.image):
        parser.error('Invalid container image reference')
    if not all(1 <= n <= 8192 for n in (args.width, args.height, args.dpi)) or not 1 <= args.fps <= 120:
        parser.error('Invalid display dimensions, density or FPS')
    if args.adb_port is not None and not 1 <= args.adb_port <= 65535:
        parser.error('Invalid ADB port')
    verify_kernel()
    host = os.environ.get('DOCKER_HOST', 'unix:///var/run/docker.sock')
    if not host.startswith('unix:///'):
        parser.error('Requires a local guest Docker daemon over a Unix socket')
    docker = ['docker', '--host', host]

    def run(arguments, check=True):
        result = subprocess.run(docker + arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=120)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace').strip())
        return result

    if run(['container', 'inspect', args.name], check=False).returncode == 0:
        raise ValueError('Container name already exists; choose another name or manage it explicitly')
    info = run(['info', '--format', '{{.KernelVersion}}\n{{.DockerRootDir}}']).stdout.decode().splitlines()
    if len(info) != 2 or info[0] != os.uname().release:
        raise ValueError('Docker daemon is not running in this guest')
    args.data.mkdir(parents=True, exist_ok=True)
    data = args.data.resolve()
    if ',' in str(data) or '\n' in str(data):
        parser.error('Data path cannot contain commas or newlines in Docker mount syntax')
    for path in (data, Path(info[1])):
        filesystem = subprocess.check_output(['findmnt', '-n', '-o', 'FSTYPE', '-T', str(path)], text=True).strip()
        if filesystem != 'ext4':
            raise ValueError(f'Use ext4-backed container data and Docker data-root: {path}')
    # The bind mount changes only this new Android container, not its image.
    original = image_properties(run, args.image)
    parent = Path('/.droid/state/term-redroid-config')
    parent.mkdir(mode=0o700, exist_ok=True)
    config = Path(tempfile.mkdtemp(prefix=args.name + '-', dir=parent)) / 'build.prop'
    config.write_bytes(build_properties(original, args.prime_shader_cache))
    config.chmod(0o600)
    command = ['run', '-d', '--pull=never', '--name', args.name, '--privileged', '--shm-size=128m',
               '--mount', f'type=bind,src={data},dst=/data',
               '--mount', f'type=bind,src={config},dst=/system/build.prop,readonly']
    if args.adb_port:
        command += ['--publish', f'127.0.0.1:{args.adb_port}:5555']
    command += [args.image, 'androidboot.use_memfd=1', 'androidboot.redroid_gpu_mode=guest',
                f'androidboot.redroid_width={args.width}', f'androidboot.redroid_height={args.height}',
                f'androidboot.redroid_fps={args.fps}', f'androidboot.redroid_dpi={args.dpi}']
    print(run(command).stdout.decode().strip())
    print(f'Boot configuration: {config}')


if __name__ == '__main__':
    main()
