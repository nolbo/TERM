import argparse
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import struct
import sys
from types import SimpleNamespace
import unittest
from unittest import mock
import time


REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('redroid_probe', REPO / 'tests/integration/redroid_probe.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
control_spec = importlib.util.spec_from_file_location('redroid_control', REPO / 'tests/integration/redroid-guest-control.py')
guest_control = importlib.util.module_from_spec(control_spec)
control_spec.loader.exec_module(guest_control)
launch_spec = importlib.util.spec_from_file_location('redroid_launch', REPO / 'image/scripts/launch-redroid.py')
launcher = importlib.util.module_from_spec(launch_spec)
launch_spec.loader.exec_module(launcher)


class RedroidProbeTests(unittest.TestCase):
    def test_booted_android_with_a_black_screenshot_is_not_ui_success(self):
        black = struct.pack('<IIII', 8, 8, 1, 0) + b'\0\0\0\xff' * 64
        with self.assertRaisesRegex(ValueError, 'blank or flat'):
            probe.inspect_frame(black)

    def test_frame_requires_complete_pixels_and_visible_color_variation(self):
        pixels = b''.join(bytes((i, 255 - i, i, 255)) for i in range(64))
        frame = struct.pack('<IIII', 8, 8, 1, 0) + pixels
        self.assertEqual(64, probe.inspect_frame(frame)['rgbColors'])
        for broken in (frame[:10], frame[:-1], struct.pack('<IIII', 8, 8, 22, 0) + pixels):
            with self.subTest(length=len(broken)), self.assertRaises(ValueError):
                probe.inspect_frame(broken)

    def test_large_response_survives_partial_virtserial_writes(self):
        chunks = []
        def write(packet):
            chunks.append(bytes(packet[:32768]))
            return len(chunks[-1])
        channel = SimpleNamespace(write=write)
        response = {'id': 1, 'data': 'a' * 100000}
        guest_control.send(channel, response)
        self.assertEqual(response, json.loads(b''.join(chunks)))
        self.assertGreater(len(chunks), 1)

    def test_command_timeout_closes_pipes_held_by_child_processes(self):
        code = ('import subprocess,sys,time; '
                'subprocess.Popen([sys.executable,"-c","import time; time.sleep(10)"]); '
                'time.sleep(10)')
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired):
            guest_control.execute([sys.executable, '-c', code], .2)
        self.assertLess(time.monotonic() - started, 3)

    def test_unnamed_console_port_does_not_prevent_finding_diagnostic_port(self):
        with tempfile.TemporaryDirectory() as temp:
            ports = Path(temp)
            (ports / 'vport0p0').mkdir()
            (ports / 'vport0p1').mkdir()
            (ports / 'vport0p1/name').write_text('term.redroid.test\n')
            self.assertEqual(Path('/dev/vport0p1'), guest_control.find_port(ports))

    def test_handshake_and_fragmented_response_preserve_structured_argv(self):
        connection = mock.Mock()
        response = {'id': 1, 'code': 0, 'data': base64.b64encode(b'answer\n').decode()}
        packet = json.dumps(response).encode() + b'\n'
        connection.recv.side_effect = [b'{"id":0,', b'"ready":true}\n', packet[:7], packet[7:]]
        with mock.patch.object(probe.socket, 'socket', return_value=connection):
            control = probe.Control(Path('/tmp/test-control'), mock.Mock(poll=lambda: None), time.monotonic() + 5)
        connection.sendall.assert_not_called()
        self.assertEqual((0, b'answer\n'), control.run(['printf', 'a b; $(not-a-shell)']))
        request = json.loads(connection.sendall.call_args.args[0])
        self.assertEqual(['printf', 'a b; $(not-a-shell)'], request['argv'])

    def test_mismatched_response_cannot_be_accepted_as_current_probe(self):
        control = probe.Control.__new__(probe.Control)
        control.sock = mock.Mock()
        control.sequence = 1
        control.pending = b'{"id":3,"code":0,"data":"MQo="}\n'
        with self.assertRaisesRegex(RuntimeError, 'Guest command failed'):
            control.run(['getprop', 'sys.boot_completed'])

    def test_timed_out_previous_command_cannot_satisfy_a_new_request(self):
        control = probe.Control.__new__(probe.Control)
        control.sock = mock.Mock()
        control.sequence = 1
        control.pending = (b'{"id":1,"code":0,"data":"b2xk"}\n'
                           b'{"id":2,"code":0,"data":"bmV3"}\n')
        self.assertEqual((0, b'new'), control.run(['getprop', 'sys.boot_completed']))

    def test_shader_override_preserves_security_properties_and_imports(self):
        original = (b'# original\nro.secure=1\nro.debuggable=0\nimport /vendor/default.prop\n'
                    b' service.sf.prime_shader_cache = 1\nro.other=unchanged')
        merged = launcher.build_properties(original)
        self.assertIn(b'ro.secure=1\nro.debuggable=0\nimport /vendor/default.prop\n', merged)
        self.assertIn(b'ro.other=unchanged\n', merged)
        self.assertEqual(1, merged.count(b'service.sf.prime_shader_cache'))
        self.assertTrue(merged.endswith(b'service.sf.prime_shader_cache=0\n'))
        self.assertEqual(b'service.sf.prime_shader_cache=1\n', launcher.build_properties(b'', True))

    def test_malformed_or_oversized_properties_are_rejected(self):
        for content in (b'ro.secure=1\0ignored', b'x' * (1024 * 1024 + 1)):
            with self.assertRaises(ValueError):
                launcher.build_properties(content)

    def test_inspection_reads_stopped_image_without_executing_android(self):
        calls = []
        container = 'a' * 64

        def run(arguments):
            calls.append(arguments)
            if arguments[0] == 'cp':
                Path(arguments[2]).write_bytes(b'ro.secure=1\n')
            return SimpleNamespace(stdout=(container + '\n').encode())

        self.assertEqual(b'ro.secure=1\n', launcher.image_properties(run, 'redroid/redroid:test'))
        self.assertEqual('create', calls[0][0])
        self.assertEqual(container + ':/system/build.prop', calls[1][1])
        self.assertEqual(['rm', container], calls[-1])

    def test_inspection_container_is_removed_when_file_copy_fails(self):
        container = 'b' * 64
        run = mock.Mock(side_effect=[SimpleNamespace(stdout=container.encode()),
                                     RuntimeError('copy failed'), SimpleNamespace(stdout=b'')])
        with self.assertRaisesRegex(RuntimeError, 'copy failed'):
            launcher.image_properties(run, 'redroid/redroid:test')
        self.assertEqual(mock.call(['rm', container]), run.call_args)

    def test_full_watchdog_cannot_be_confused_with_pre_watchdog(self):
        self.assertEqual([], probe.full_watchdogs('/data/system/dropbox/system_server_pre_watchdog@1.txt.gz'))
        self.assertEqual(['/data/system/dropbox/system_server_watchdog@2.txt.gz'], probe.full_watchdogs(
            '/data/system/dropbox/system_server_pre_watchdog@1.txt.gz\n'
            '/data/system/dropbox/system_server_watchdog@2.txt.gz\n'))

    def test_core_services_must_all_be_found(self):
        self.assertTrue(probe.check_services('Service activity: found\nService package: found\nService display: found\n'))
        self.assertFalse(probe.check_services('Service activity: found\nService package: found\nService display: not found\n'))

    def test_zero_exit_or_error_text_alone_cannot_pass_settings_launch(self):
        self.assertTrue(probe.settings_started('Status: ok\nActivity: com.android.settings/.Settings\nComplete\n'))
        self.assertFalse(probe.settings_started('Error: Activity not started\n'))
        self.assertFalse(probe.settings_started('Status: ok\nActivity: example/.OtherActivity\n'))

    def test_launched_settings_cannot_pass_after_anr_returns_to_launcher(self):
        self.assertTrue(probe.settings_resumed('  topResumedActivity=ActivityRecord{123 u0 com.android.settings/.Settings t7}\n'))
        self.assertTrue(probe.settings_resumed('  mResumedActivity: ActivityRecord{123 u0 com.android.settings/.Settings t7}\n'))
        self.assertFalse(probe.settings_resumed('History: com.android.settings/.Settings\n'
                                              ' topResumedActivity=ActivityRecord{321 u0 com.android.launcher3/.Launcher t6}\n'))

    def test_test_data_cannot_escape_its_guest_directory(self):
        for name in ('../docker', '/data', 'a b', 'x;touch', 'A', ''):
            with self.subTest(name=name), self.assertRaises(argparse.ArgumentTypeError):
                probe.data_name(name)
        self.assertEqual('redroid-test1', probe.data_name('redroid-test1'))

    def test_wrong_architecture_docker_binary_is_rejected_before_mkfs(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            binaries = root / 'docker'
            binaries.mkdir()
            (binaries / 'docker').write_bytes(b'\x7fELF\x02\x01' + b'\0' * 12 + (62).to_bytes(2, 'little'))
            work = root / 'work'
            work.mkdir()
            with self.assertRaisesRegex(ValueError, 'ARM64 Docker'):
                probe.new_seed(work, SimpleNamespace(docker_bin=binaries, hello_archive=root / 'hello.tar',
                                                     redroid_archive=root / 'redroid.tar'))


if __name__ == '__main__':
    unittest.main()
