#!/usr/bin/env python3
"""Optional, offline ReDroid check in an independent QEMU Ubuntu guest.

Never installs the diagnostic channel in a release image or boots an installed
environment. Input state is cloned; only a newly created work directory is RW.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import socket
import struct
import subprocess
import time
import uuid

REPO = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PREFIX = '/upper/usr/local/lib/droid-container-test'
BINARIES = ('docker', 'dockerd', 'containerd', 'ctr', 'runc',
            'containerd-shim-runc-v2', 'docker-init', 'docker-proxy')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def data_name(value):
    if not re.fullmatch('[a-z0-9-]{1,64}', value):
        raise argparse.ArgumentTypeError('Expected a test data directory name, without slashes')
    return value


def check_services(output):
    return all(f'Service {name}: found' in output.splitlines()
               for name in ('activity', 'package', 'display'))


def full_watchdogs(output):
    return [line for line in output.splitlines()
            if re.search(r'/system_server_watchdog@', line)]


def settings_started(output):
    return (re.search(r'^Status: ok\s*$', output, re.M) is not None
            and re.search(r'^Activity: com\.android\.settings/', output, re.M) is not None)


def settings_resumed(output):
    return re.search(r'^\s*(?:topResumedActivity|mResumedActivity|ResumedActivity)\s*[:=]'
                     r'[^\n]*\bcom\.android\.settings/', output, re.M) is not None


def inspect_frame(frame):
    """Check Android 15 screencap's raw header and reject blank/flat output."""
    if len(frame) < 16:
        raise ValueError('Truncated raw screen header')
    width, height, pixel_format, color_space = struct.unpack('<IIII', frame[:16])
    stride = {1: 4, 2: 4, 3: 3}.get(pixel_format)  # RGBA_8888, RGBX_8888, RGB_888
    if not stride or not width or not height or len(frame) != 16 + width * height * stride:
        raise ValueError('Unsupported or truncated raw screen buffer')
    colors = {frame[i:i + 3] for i in range(16, len(frame), stride)}
    if len(colors) < 16:
        raise ValueError('ReDroid screen is blank or flat; UI validation failed')
    return {'width': width, 'height': height, 'pixelFormat': pixel_format,
            'colorSpace': color_space, 'rgbColors': len(colors)}


def diagnostic_requests(control, work):
    """Operator-owned files allow live diagnostics without interrupting a test."""
    for path in sorted((work / 'requests').glob('*.json')):
        if path.stat().st_size > 16384:
            raise ValueError('Oversized diagnostic request')
        request = json.loads(path.read_text())
        try:
            code, output = control.run(request['argv'], timeout=request.get('timeout', 60))
            response = {'code': code, 'data': base64.b64encode(output).decode()}
        except (TimeoutError, RuntimeError) as exc:
            response = {'error': str(exc)}
        path.with_suffix('.response').write_text(json.dumps(response) + '\n')
        path.rename(path.with_suffix('.done'))


class Control:
    def __init__(self, path, process, deadline):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.pending = b''
        self.sequence = 0
        while process.poll() is None and time.monotonic() < deadline:
            try:
                self.sock.connect(str(path))
                break
            except (FileNotFoundError, ConnectionRefusedError):
                time.sleep(.2)
        else:
            raise RuntimeError('QEMU diagnostic socket unavailable')
        if self.receive(deadline) != {'id': 0, 'ready': True}:
            raise RuntimeError('Invalid guest-ready handshake')

    def receive(self, deadline):
        while b'\n' not in self.pending:
            if time.monotonic() >= deadline:
                raise TimeoutError('Guest response wall-clock deadline')
            self.sock.settimeout(min(5, deadline - time.monotonic()))
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                raise RuntimeError('Guest diagnostic channel disconnected')
            self.pending += chunk
            if len(self.pending) > 750000:
                raise ValueError('Oversized guest response')
        line, self.pending = self.pending.split(b'\n', 1)
        return json.loads(line)

    def run(self, argv, timeout=60, check=False):
        self.sequence += 1
        self.sock.sendall(json.dumps({'id': self.sequence, 'argv': argv,
                                     'timeout': timeout}).encode() + b'\n')
        deadline = time.monotonic() + timeout + 15
        response = self.receive(deadline)
        while isinstance(response.get('id'), int) and response['id'] < self.sequence:
            response = self.receive(deadline)
        if response.get('id') != self.sequence or 'error' in response:
            raise RuntimeError(f'Guest command failed: {response}')
        output = base64.b64decode(response['data'], validate=True)
        if check and response['code']:
            raise RuntimeError(f'Guest command {argv!r}: {output.decode(errors="replace")}')
        return response['code'], output


def new_seed(work, args):
    if not all((args.docker_bin, args.hello_archive, args.redroid_archive)):
        raise ValueError('Provide --fixture-state, or --docker-bin/--hello-archive/--redroid-archive')
    seed = work / 'seed'
    target = seed / PREFIX.lstrip('/') / 'bin'
    target.mkdir(parents=True)
    for name in BINARIES:
        source = args.docker_bin / name
        with source.open('rb') as stream:
            header = stream.read(20)
        if header[:6] != b'\x7fELF\x02\x01' or int.from_bytes(header[18:20], 'little') != 183:
            raise ValueError(f'Expected static ARM64 Docker binary: {name}')
        shutil.copyfile(source, target / name)
        (target / name).chmod(0o755)
    (seed / 'fixtures').mkdir()
    for source, name in ((args.hello_archive, 'hello-world.tar'), (args.redroid_archive, 'redroid.tar')):
        if not source.is_file() or source.stat().st_size > 4 * 1024**3:
            raise ValueError(f'Invalid fixture archive: {source}')
        shutil.copyfile(source, seed / 'fixtures' / name)
    (seed / 'work').mkdir()
    return seed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image-dir', type=Path, required=True, help='Verified candidate with extracted raw images')
    parser.add_argument('--work-dir', type=Path, required=True, help='Must not exist')
    parser.add_argument('--tool-image', required=True, help='Local Docker image with QEMU ARM64 and e2fsprogs')
    parser.add_argument('--fixture-state', type=Path, help='Disposable fixture template, cloned before use')
    parser.add_argument('--docker-bin', type=Path, help='Official static ARM64 Docker binaries')
    parser.add_argument('--hello-archive', type=Path)
    parser.add_argument('--redroid-archive', type=Path)
    parser.add_argument('--prepare-only', action='store_true', help='Import fixtures, then stop without Android')
    parser.add_argument('--prime-shader-cache', action='store_true', help='Enable shader priming for A/B diagnosis')
    parser.add_argument('--fps', type=int, default=10, help='Android virtual display refresh rate')
    parser.add_argument('--cpus', type=int, default=4, help='QEMU vCPUs and outer CPU quota')
    parser.add_argument('--settle-seconds', type=int, default=120,
                        help='Allow Android background startup to settle before launching Settings')
    parser.add_argument('--data-name', type=data_name, default='redroid-' + uuid.uuid4().hex[:12])
    parser.add_argument('--boot-timeout', type=int, default=1200)
    parser.add_argument('--diagnostic-hold-seconds', type=int, default=0,
                        help='Keep the owned test VM available for live diagnostics after Settings starts')
    parser.add_argument('--sudo', action='store_true', help='Run Docker client through sudo -n')
    args = parser.parse_args()
    if (args.boot_timeout <= 0 or not 0 <= args.diagnostic_hold_seconds <= 600
            or not 1 <= args.fps <= 120 or not 1 <= args.cpus <= 8
            or not 0 <= args.settle_seconds <= 600):
        parser.error('Invalid boot timeout or diagnostic hold duration')
    if args.work_dir.exists():
        raise FileExistsError('Work directory already exists; preserve it and choose a new one')
    images = args.image_dir.resolve()
    verifier = runpy.run_path(str(REPO / 'image/scripts/verify-avf-release.py'))
    verifier['verify_directory'](images, metadata_only=True, require_container_kernel=True)
    manifest = verifier['manifest'](verifier['read_metadata'](images / 'manifest.properties'),
                                    verifier['catalog'](verifier['read_metadata'](images / 'catalog.properties')))
    for key, filename in (('kernel', 'vmlinuz'), ('initrd', 'initrd.img'),
                          ('system', 'system.img'), ('config', 'config.img')):
        if digest(images / filename) != manifest[key + '.sha256']:
            raise ValueError(f'Raw image checksum mismatch: {filename}')
    work = args.work_dir.resolve()
    work.mkdir(mode=0o700)
    (work / 'requests').mkdir(mode=0o700)
    docker = ['sudo', '-n', 'docker'] if args.sudo else ['docker']
    common = docker + ['run', '--rm', '--pull=never', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}',
                       '--cap-drop=ALL', '--security-opt', 'no-new-privileges', '-v', f'{work}:/work']

    def tool(binary, *arguments, allowed=(0,)):
        result = subprocess.run(common + ['--entrypoint', binary, args.tool_image, *arguments],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
        if result.returncode not in allowed:
            raise RuntimeError(result.stdout.decode(errors='replace'))
        return result.stdout.decode(errors='replace')

    state = work / 'state.img'
    if args.fixture_state:
        if not args.fixture_state.is_file():
            raise ValueError('Fixture state must be a regular file')
        subprocess.run(['cp', '--sparse=always', '--reflink=auto', '--', str(args.fixture_state), str(state)], check=True)
        tool('/sbin/e2fsck', '-p', '/work/state.img', allowed=(0, 1))
    else:
        new_seed(work, args)
        with state.open('xb') as stream:
            stream.truncate(16 * 1024**3)
        tool('/sbin/mkfs.ext4', '-F', '-L', 'droid-state', '-O', '^metadata_csum_seed,^orphan_file',
             '-d', '/work/seed', '/work/state.img')
    commands = []
    for name in ('redroid-guest-init', 'redroid-guest-control.py', 'launch-redroid.py'):
        source = REPO / 'image/scripts' / name if name == 'launch-redroid.py' else HERE / name
        shutil.copyfile(source, work / name)
        # Replacing files changes only the independent test copy.
        commands += [f'rm {PREFIX}/{name}', f'write /work/{name} {PREFIX}/{name}',
                     f'set_inode_field {PREFIX}/{name} mode 0100755',
                     f'set_inode_field {PREFIX}/{name} uid 0', f'set_inode_field {PREFIX}/{name} gid 0']
    (work / 'install.debugfs').write_text('\n'.join(commands) + '\n')
    tool('/sbin/debugfs', '-w', '-f', '/work/install.debugfs', '/work/state.img')
    name = 'term-redroid-probe-' + uuid.uuid4().hex[:12]
    command = common + ['--name', name, '--cpus', str(args.cpus), '--memory', '6g', '-v', f'{images}:/images:ro',
                        '--entrypoint', 'qemu-system-aarch64', args.tool_image,
                        '-machine', 'virt', '-cpu', 'max', '-accel', 'tcg',
                        '-smp', str(args.cpus), '-m', '4096', '-display', 'none', '-monitor', 'none', '-nic', 'none',
                        '-no-reboot', '-kernel', '/images/vmlinuz', '-initrd', '/images/initrd.img',
                        '-append', 'root=/dev/vda rootfstype=ext4 ro console=hvc0 term.redroid-test=1 '
                        f'term.redroid-data={args.data_name} term.redroid-prime={int(args.prime_shader_cache)} '
                        f'term.redroid-fps={args.fps} '
                        f'init={PREFIX.removeprefix("/upper")}/redroid-guest-init',
                        '-device', 'virtio-serial-pci', '-chardev', 'stdio,id=console,signal=off',
                        '-device', 'virtconsole,chardev=console', '-device', 'virtio-rng-pci',
                        '-chardev', 'socket,id=control,path=/work/control.sock,server=on,wait=off',
                        '-device', 'virtserialport,chardev=control,name=term.redroid.test']
    for i, (path, readonly) in enumerate((('/images/system.img', True), ('/work/state.img', False),
                                         ('/images/config.img', True))):
        command += ['-drive', f'if=none,id=disk{i},file={path},format=raw,readonly={"on" if readonly else "off"}',
                    '-device', f'virtio-blk-pci,drive=disk{i}']
    result = {'mode': 'qemu-tcg-wall-clock', 'cpu': 'max',
              'kernelSha256': manifest['kernel.sha256'], 'redroidBootCompleted': False,
              'preparedOnly': args.prepare_only, 'androidData': args.data_name,
              'primeShaderCache': args.prime_shader_cache, 'displayFps': args.fps,
              'vcpus': args.cpus, 'settleSeconds': args.settle_seconds}
    result['qemuVersion'] = tool('/usr/bin/qemu-system-aarch64', '--version').splitlines()[0]
    started = time.monotonic()
    control = None
    with (work / 'console.log').open('xb') as console:
        print('QEMU test:', name, flush=True)
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=console, stderr=subprocess.STDOUT)
        try:
            control = Control(work / 'control.sock', process, time.monotonic() + 300)
            begin = time.monotonic()
            control.run(['python3', '-c', 'import os; [os.getpid() for _ in range(10000)]'], check=True)
            result['getpid10000WallSeconds'] = round(time.monotonic() - begin, 4)
            print('getpid10000WallSeconds=', result['getpid10000WallSeconds'], flush=True)
            control.run(['touch', '/run/term-start-docker'], check=True)
            fixture_deadline = time.monotonic() + 1800
            while control.run(['test', '-e', '/run/term-fixture-ready'])[0]:
                if process.poll() is not None or time.monotonic() > fixture_deadline:
                    raise RuntimeError('Docker fixture preparation failed')
                time.sleep(5)
            result['fixturePrepared'] = True
            _, version = control.run(['docker', 'version', '--format', '{{.Server.Version}}'], check=True)
            result['dockerVersion'] = version.decode().strip()
            _, identity = control.run(['docker', 'image', 'inspect', 'redroid/redroid:15.0.0-latest',
                                       '--format', '{{.Id}}'], check=True)
            result['redroidImageId'] = identity.decode().strip()
            _, layers = control.run(['docker', 'image', 'inspect', 'redroid/redroid:15.0.0-latest',
                                     '--format', '{{json .RootFS.Layers}}'], check=True)
            result['redroidRootfsLayers'] = json.loads(layers)
            if not args.prepare_only:
                control.run(['touch', '/run/term-run-redroid'], check=True)
                boot_start = time.monotonic()
                while time.monotonic() - boot_start < args.boot_timeout:
                    diagnostic_requests(control, work)
                    code, output = control.run(['docker', 'exec', 'term-redroid-test', 'getprop', 'sys.boot_completed'])
                    print('bootElapsed=', round(time.monotonic() - boot_start), 'boot=', output.decode(errors='replace').strip(), flush=True)
                    if code == 0 and output.strip() == b'1':
                        break
                    code, output = control.run(['docker', 'exec', 'term-redroid-test', 'find',
                                                 '/data/system/dropbox', '-maxdepth', '1', '-name', '*watchdog*'])
                    if code == 0 and full_watchdogs(output.decode(errors='replace')):
                        result['watchdogs'] = output.decode(errors='replace')
                        raise RuntimeError('Android watchdog restarted system_server during boot')
                    time.sleep(15)
                else:
                    raise RuntimeError('ReDroid boot deadline')
                result['redroidBootCompleted'] = True
                result['bootWallSeconds'] = round(time.monotonic() - boot_start, 2)
                _, applied = control.run(['docker', 'exec', 'term-redroid-test', 'getprop',
                                          'service.sf.prime_shader_cache'], check=True)
                result['appliedPrimeShaderCache'] = applied.decode().strip()
                if result['appliedPrimeShaderCache'] != str(int(args.prime_shader_cache)):
                    raise RuntimeError('Shader priming setting was not applied in Android')
                _, output = control.run(['docker', 'exec', 'term-redroid-test', 'sh', '-c',
                                         'service check activity; service check package; service check display'], check=True)
                result['services'] = output.decode(errors='replace')
                if not check_services(result['services']):
                    raise RuntimeError('Android core services unavailable')
                until = time.monotonic() + args.settle_seconds
                while time.monotonic() < until:
                    diagnostic_requests(control, work)
                    time.sleep(1)
                control.run(['docker', 'exec', 'term-redroid-test', 'input', 'keyevent', 'WAKEUP'], check=True)
                _, output = control.run(['docker', 'exec', 'term-redroid-test', 'am', 'start', '-W',
                                         '-a', 'android.settings.SETTINGS'], timeout=120, check=True)
                result['settingsLaunch'] = output.decode(errors='replace')
                if not settings_started(result['settingsLaunch']):
                    raise RuntimeError('Settings activity did not start successfully')
                until = time.monotonic() + args.diagnostic_hold_seconds
                while time.monotonic() < until:
                    diagnostic_requests(control, work)
                    time.sleep(1)
                # Wake immediately before capture without changing saved settings.
                control.run(['docker', 'exec', 'term-redroid-test', 'input', 'keyevent', 'WAKEUP'], check=True)
                time.sleep(3)
                _, output = control.run(['docker', 'exec', 'term-redroid-test', 'find', '/data/system/dropbox',
                                         '-maxdepth', '1', '-name', '*watchdog*'], check=True)
                result['watchdogs'] = output.decode(errors='replace')
                if full_watchdogs(result['watchdogs']):
                    raise RuntimeError('Android watchdog restarted system_server')
                _, raw = control.run(['docker', 'exec', 'term-redroid-test', 'screencap'], check=True)
                _, png = control.run(['docker', 'exec', 'term-redroid-test', 'screencap', '-p'], check=True)
                if not png.startswith(b'\x89PNG\r\n\x1a\n'):
                    raise RuntimeError('Invalid screenshot')
                (work / 'screen.png').write_bytes(png)
                result['screenPixels'] = inspect_frame(raw)
                _, activity = control.run(['docker', 'exec', 'term-redroid-test', 'dumpsys',
                                            'activity', 'activities'], check=True)
                result['settingsResumed'] = settings_resumed(activity.decode(errors='replace'))
                if not result['settingsResumed']:
                    raise RuntimeError('Settings no longer resumed; a home screen is not Settings UI success')
                result['screenReview'] = 'required'
                print('TERM_REDROID_BOOT_OK', flush=True)
            result['passed'] = True
        except Exception as exc:
            result['error'] = str(exc)
            if control:
                for label, argv in (('androidLog', ['docker', 'exec', 'term-redroid-test', 'logcat', '-b', 'system', '-d', '-t', '80']),
                                    ('cpuPressure', ['cat', '/proc/pressure/cpu'])):
                    try:
                        _, output = control.run(argv)
                        (work / (label + '.log')).write_bytes(output)
                    except Exception:
                        pass
            raise
        finally:
            result['totalWallSeconds'] = round(time.monotonic() - started, 2)
            (work / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            if control:
                try:
                    control.run(['docker', 'stop', '--timeout', '1', 'term-redroid-test'])
                    control.run(['sync'])
                    control.run(['poweroff', '-f'])
                except Exception:
                    pass
                control.sock.close()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                pass
            subprocess.run(docker + ['rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)


if __name__ == '__main__':
    main()
