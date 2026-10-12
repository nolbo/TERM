import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO / 'image/scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


factory = load('distro_factory', 'build-avf-image.py')
verifier = load('distro_verifier', 'verify-avf-release.py')
rootfs = load('distro_rootfs', 'prepare-avf-rootfs.py')


class DistributionTests(unittest.TestCase):
    def test_factory_and_catalog_require_matching_distribution_and_common_capabilities(self):
        catalogs = []
        with tempfile.TemporaryDirectory() as temporary:
            for distro in ('debian', 'ubuntu', 'archlinux'):
                root = Path(temporary) / distro
                root.mkdir()
                for name in factory.ARTIFACTS.values():
                    (root / name).write_bytes((distro + name).encode() * 100)
                tag = 'avf-' + distro + '-test'
                factory.write_release(root, tag, verifier.PUBLIC_BASE + tag + '/', factory.SOURCE_URL, 'a' * 64, distro)
                approved = verifier.verify_directory(root)
                manifest = verifier.manifest((root / 'manifest.properties').read_bytes(), approved)
                self.assertEqual(distro, manifest['distribution'])
                self.assertEqual('1', manifest['packageInitializationVersion'])
                self.assertEqual(distro + '-arm64-current', approved['id'])
                catalogs.append((root / 'catalog.properties').read_bytes())
                wrong = dict(approved, id=('ubuntu' if distro == 'debian' else 'debian') + '-arm64-current')
                with self.assertRaisesRegex(ValueError, 'catalog-selected'):
                    verifier.manifest((root / 'manifest.properties').read_bytes(), wrong)
            merged = b''
            for catalog in catalogs:
                merged = verifier.merge_catalog(merged, catalog)
            entries = verifier.catalog_entries(merged)
            self.assertEqual({'debian', 'ubuntu', 'archlinux'}, set(entries))
            updated = verifier.merge_catalog(merged, catalogs[1])
            self.assertEqual(entries, verifier.catalog_entries(updated))
            for malformed in [merged.replace(b'ubuntu.id=ubuntu', b'ubuntu.id=debian'),
                              merged.replace(b'distributions=debian,ubuntu,archlinux', b'distributions=debian,debian'),
                              merged + b'fedora.id=fedora-arm64-current\n']:
                with self.assertRaises(ValueError):
                    verifier.catalog_entries(malformed)

    def test_userspace_and_avf_pin_the_same_ubuntu_and_arch_base_rootfs(self):
        source = (REPO / 'android-runtime/src/main/kotlin/dev/terminal/runtime/rootless/RootlessDistribution.kt').read_text()
        for distro, profile in rootfs.PROFILES.items():
            with self.subTest(distro=distro):
                self.assertIn('"' + profile['archiveRoot'] + '", "' + profile['release'] + '"', source)
                self.assertIn(profile['sha256'], source)

    def test_rootfs_checksum_failure_stops_before_unpack_or_guest_execution(self):
        data = io.BytesIO(b'untrusted rootfs')
        data.url = 'https://example.invalid/rootfs.tar.xz'
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(rootfs.urllib.request, 'urlopen', return_value=data), \
                mock.patch.object(rootfs.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                rootfs.prepare('ubuntu', Path(temporary))
            run.assert_not_called()

    def test_package_initialization_dispatch_failure_retry_and_idempotence(self):
        original = (REPO / 'image/scripts/initialize-avf-packages.sh').read_text()
        for distro, manager in [('debian', 'apt-get'), ('ubuntu', 'apt-get'), ('archlinux', 'pacman')]:
            with self.subTest(distro=distro), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                binaries = root / 'bin'
                binaries.mkdir()
                log = root / 'commands'
                distribution = root / 'distribution'
                distribution.write_text(distro)
                state = root / 'state'
                for name, content in [('id', '#!/bin/sh\necho 0\n'),
                                      ('sync', '#!/bin/sh\nexit 0\n'),
                                      ('apt-get', '#!/bin/sh\necho "apt-get $*" >> "$TERM_TEST_LOG"\nexit "$TERM_TEST_RESULT"\n'),
                                      ('pacman', '#!/bin/sh\necho "pacman $*" >> "$TERM_TEST_LOG"\nexit "$TERM_TEST_RESULT"\n')]:
                    path = binaries / name
                    path.write_text(content)
                    path.chmod(0o755)
                # Isolate fixed guest paths and executables in a task-owned fixture.
                script = root / 'initialize.sh'
                script.write_text(original.replace('export PATH=/usr/sbin:/usr/bin:/sbin:/bin', f'export PATH={binaries}:/usr/bin:/bin')
                                  .replace('/etc/droid/distribution', str(distribution))
                                  .replace('/var/lib/droid/package-initialization', str(state)))
                env = dict(os.environ, TERM_TEST_LOG=str(log), TERM_TEST_RESULT='1')
                result = subprocess.run(['sh', str(script)], env=env, capture_output=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((state / 'complete').exists())
                env['TERM_TEST_RESULT'] = '0'
                subprocess.run(['sh', str(script)], env=env, check=True, capture_output=True)
                receipt = (state / 'complete').read_bytes()
                commands = log.read_text()
                self.assertTrue(commands.startswith(manager + ' '))
                if manager == 'apt-get':
                    self.assertIn('APT::Update::Error-Mode=any', commands)
                    self.assertIn('Acquire::PDiffs=false', commands)
                else:
                    self.assertIn('--sync --refresh --noconfirm', commands)
                    self.assertNotIn('--sysupgrade', commands)
                env['TERM_TEST_RESULT'] = '1'
                subprocess.run(['sh', str(script)], env=env, check=True, capture_output=True)
                self.assertEqual(receipt, (state / 'complete').read_bytes())
                self.assertEqual(commands, log.read_text())


if __name__ == '__main__':
    unittest.main()
