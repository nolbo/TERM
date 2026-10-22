import copy
import gzip
import importlib.util
import io
import json
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO / 'image/scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


kernel = load('kernel_contract', 'avf_kernel.py')
factory = load('kernel_factory', 'build-avf-image.py')
verifier = load('kernel_verifier', 'verify-avf-release.py')


def config_bytes(values):
    return ''.join(f'# {key} is not set\n' if value == 'n' else f'{key}={value}\n'
                   for key, value in values.items()).encode()


def fixture_bundle(root):
    # Synthetic Image is for parser/fail-closed tests, never boot evidence.
    config = config_bytes(kernel.requirements())
    image = bytearray(64)
    image[56:60] = b'ARM\x64'
    release = '6.12.60-term-containers-test'
    image += b'Linux version ' + release.encode() + b' (test)\x00'
    image += b'IKCFG_ST' + gzip.compress(config) + b'IKCFG_ED'
    (root / 'vmlinuz').write_bytes(image)
    (root / 'initrd.img').write_bytes(b'synthetic paired module ramdisk')
    (root / 'kernel.config').write_bytes(config)
    (root / 'source-manifest.xml').write_bytes(b'<manifest/>')
    metadata = {
        'formatVersion': 1, 'buildSystem': 'Android Kleaf', 'kernelRelease': release,
        'commonRevision': kernel.SOURCE['commonRevision'],
        'baseKernelSha256': kernel.SOURCE['baseKernelSha256'],
        'fragmentSha256': kernel.sha256(kernel.KERNEL_DIR / 'containers.fragment'),
        'requiredConfig': kernel.requirements(),
        'artifacts': {name: kernel.sha256(root / name) for name in
                      ('vmlinuz', 'initrd.img', 'kernel.config', 'source-manifest.xml')},
    }
    (root / 'kernel-build.json').write_text(json.dumps(metadata))
    return metadata


class ContainerKernelTests(unittest.TestCase):
    def test_fragment_covers_reported_gaps_and_preserves_guest_contract(self):
        required = kernel.requirements()
        for flag in ('PID_NS', 'IPC_NS', 'SYSVIPC', 'POSIX_MQUEUE', 'CGROUP_PIDS',
                     'NETFILTER_XT_MATCH_ADDRTYPE', 'DMABUF_HEAPS_SYSTEM', 'NFT_COMPAT',
                     'NF_TABLES', 'BRIDGE_NETFILTER', 'USER_NS', 'CGROUP_DEVICE',
                     'ANDROID_BINDER_IPC', 'ANDROID_BINDERFS', 'MEMFD_CREATE', 'SECCOMP_FILTER', 'IPV6',
                     'OVERLAY_FS', 'ARM64_4K_PAGES', 'VIRTIO_VSOCKETS', 'CFI_CLANG'):
            self.assertEqual('y', required['CONFIG_' + flag])

    def test_ipc_namespaces_use_official_c_binder_without_the_unlinkable_rust_module(self):
        values = kernel.requirements()
        self.assertEqual('n', values['CONFIG_ANDROID_BINDER_IPC_RUST'])
        values['CONFIG_ANDROID_BINDER_IPC_RUST'] = 'm'
        with self.assertRaisesRegex(ValueError, 'ANDROID_BINDER_IPC_RUST'):
            kernel.validate_config(config_bytes(values))

    def test_parser_accepts_real_kconfig_lowercase_symbols_and_rejects_duplicates(self):
        self.assertEqual({'CONFIG_FONT_8x8': 'y'}, kernel.parse_config('CONFIG_FONT_8x8=y\n'))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            kernel.parse_config('CONFIG_PID_NS=y\n# CONFIG_PID_NS is not set\n')

    def test_kconfig_silently_dropping_a_dependency_is_fatal(self):
        for flag in ('CONFIG_IPC_NS', 'CONFIG_NFT_FIB_INET', 'CONFIG_CFI_CLANG', 'CONFIG_ARM64_4K_PAGES'):
            values = kernel.requirements()
            del values[flag]
            with self.subTest(flag=flag), self.assertRaisesRegex(ValueError, flag):
                kernel.validate_config(config_bytes(values))

    def test_kernel_bundle_checks_embedded_config_release_and_every_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = fixture_bundle(root)
            self.assertEqual(metadata, kernel.validate_bundle(root))
            for name in ('vmlinuz', 'initrd.img', 'kernel.config', 'source-manifest.xml'):
                original = (root / name).read_bytes()
                (root / name).write_bytes(original + b'changed')
                with self.subTest(name=name), self.assertRaises(ValueError):
                    kernel.validate_bundle(root)
                (root / name).write_bytes(original)
            metadata['kernelRelease'] = 'wrong-module-release'
            (root / 'kernel-build.json').write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'release'):
                kernel.validate_bundle(root)

    def test_rehashed_sidecar_cannot_override_the_image_embedded_config(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = fixture_bundle(root)
            (root / 'kernel.config').write_bytes(b'CONFIG_PID_NS=n\n')
            metadata['artifacts']['kernel.config'] = kernel.sha256(root / 'kernel.config')
            (root / 'kernel-build.json').write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'final .config'):
                kernel.validate_bundle(root)

    def test_bundle_rejects_symlinks_and_wrong_source_revision(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = fixture_bundle(root)
            metadata['commonRevision'] = '0' * 40
            (root / 'kernel-build.json').write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'recipe'):
                kernel.validate_bundle(root)
            (root / 'kernel.config').unlink()
            (root / 'kernel.config').symlink_to(root / 'vmlinuz')
            with self.assertRaisesRegex(ValueError, 'unsafe'):
                kernel.validate_bundle(root)

    def test_factory_replaces_both_kernel_and_modules_together(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle, inputs = root / 'bundle', root / 'inputs'
            bundle.mkdir()
            inputs.mkdir()
            metadata = fixture_bundle(bundle)
            for name in ('vmlinuz', 'initrd.img'):
                (inputs / name).write_bytes(b'Google original')
            self.assertEqual(metadata, factory.stage_kernel_bundle(bundle, inputs))
            for name in ('vmlinuz', 'initrd.img', 'kernel.config', 'kernel-build.json'):
                self.assertEqual((bundle / name).read_bytes(), (inputs / name).read_bytes())

    def test_every_distribution_requires_built_kernel_before_docker_or_download(self):
        for distribution in ('debian', 'ubuntu', 'archlinux', None):
            args = ['factory', '--release-id', 'test', '--base-uri',
                    'https://example.invalid/test/', '--output', '/tmp/unused']
            if distribution:
                args += ['--distribution', distribution]
            with self.subTest(distribution=distribution), mock.patch('sys.argv', args), \
                    mock.patch.object(factory.subprocess, 'run') as docker, \
                    mock.patch.object(factory.urllib.request, 'urlopen') as download, \
                    mock.patch('sys.stderr', new_callable=io.StringIO) as errors:
                with self.assertRaises(SystemExit):
                    factory.main()
                self.assertIn('--kernel-bundle', errors.getvalue())
                docker.assert_not_called()
                download.assert_not_called()

    def test_new_kernel_provenance_cannot_refer_to_another_published_image(self):
        with tempfile.TemporaryDirectory() as temporary:
            metadata = fixture_bundle(Path(temporary))
            with self.assertRaisesRegex(ValueError, 'image'):
                kernel.validate_provenance(metadata, '0' * 64)
            altered = copy.deepcopy(metadata)
            del altered['requiredConfig']['CONFIG_PID_NS']
            with self.assertRaisesRegex(ValueError, 'configuration'):
                kernel.validate_provenance(altered, metadata['artifacts']['vmlinuz'])

    def test_factory_cannot_claim_probe_success_from_a_kernel_bundle_alone(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = fixture_bundle(root)
            with self.assertRaisesRegex(ValueError, 'two-boot'):
                factory.write_release(root, 'avf-ubuntu-test', verifier.PUBLIC_BASE + 'avf-ubuntu-test/',
                                      factory.SOURCE_URL, 'a' * 64, 'ubuntu', kernel_build=metadata)
            for report in (None, {'mode': 'qemu-offline', 'boots': [{'boot': 1, 'probe': 'passed'}]},
                           {'mode': 'qemu-offline', 'boots': [{'boot': 1, 'probe': 'passed'}, {'boot': 2, 'probe': 'failed'}]}):
                with self.subTest(report=report), self.assertRaisesRegex(ValueError, 'two-boot'):
                    kernel.validate_boot_report(report)

    def test_both_offline_boots_must_return_container_probe_marker(self):
        markers = ('TERM_AVF_EXT4_ROOT_OK TERM_AVF_FIRST_BOOT_OK TERM_AVF_PERSISTENCE_OK TERM_AVF_USER_SETUP_OK '
                   'TERM_AVF_PACKAGE_BASE_CLEAN_OK TERM_AVF_PACKAGE_INIT_OK TERM_AVF_VIRTIOFS_CONFIG_OK '
                   'TERM_AVF_ANDROID_STORAGE_CONFIG_OK TERM_AVF_NETWORK_CONFIG_OK TERM_AVF_GUEST_PACKAGE_OK')
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'kernel-build.json').write_text('{}')
            results = [mock.Mock(returncode=0, stdout=(markers + ' TERM_AVF_CONTAINERS_OK').encode()),
                       mock.Mock(returncode=0, stdout=markers.encode())]
            with mock.patch('pathlib.Path', return_value=root), \
                    mock.patch('subprocess.run', side_effect=results) as boots, \
                    mock.patch('sys.stdout', new_callable=io.StringIO):
                with self.assertRaisesRegex(SystemExit, 'failed'):
                    runpy.run_path(str(REPO / 'image/scripts/smoke-avf-image.py'))
                self.assertEqual(2, boots.call_count)

    def test_legacy_images_remain_readable_but_cannot_be_reused_as_container_candidates(self):
        for distribution in ('debian', 'ubuntu', 'archlinux'):
            with self.subTest(distribution=distribution), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                for name in factory.ARTIFACTS.values():
                    (root / name).write_bytes(b'legacy' * 100)
                tag = 'avf-' + distribution + '-test'
                factory.write_release(root, tag, verifier.PUBLIC_BASE + tag + '/',
                                      factory.SOURCE_URL, 'a' * 64, distribution)
                self.assertEqual(tag, verifier.verify_directory(root)['releaseId'])
                with self.assertRaisesRegex(ValueError, 'container kernel'):
                    verifier.verify_directory(root, require_container_kernel=True)


if __name__ == '__main__':
    unittest.main()
