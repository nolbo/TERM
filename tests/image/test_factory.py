import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import runpy
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("factory", REPO / "image/scripts/build-avf-image.py")
factory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(factory)


class ImageFactoryTests(unittest.TestCase):
    def archive(self, directory, members):
        path = directory / "source.tar.gz"
        with tarfile.open(path, "w:gz") as archive:
            for name, data, kind in members:
                entry = tarfile.TarInfo(name)
                entry.type = kind
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        return path

    def test_rejects_traversal_symlink_and_duplicate_before_extraction(self):
        for members in [
            [("../root_part", b"x", tarfile.REGTYPE)],
            [("root_part", b"", tarfile.SYMTYPE)],
            [("root_part", b"x", tarfile.REGTYPE), ("./root_part", b"y", tarfile.REGTYPE)],
        ]:
            with self.subTest(members=members), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                output = root / "out"
                output.mkdir()
                with self.assertRaises(ValueError):
                    factory.extract_source(self.archive(root, members), output)
                self.assertFalse((root / "root_part").exists())

    def test_rejects_bootloader_archive_with_missing_direct_boot_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "out"
            output.mkdir()
            archive = self.archive(root, [("root_part", b"filesystem", tarfile.REGTYPE)])
            with self.assertRaisesRegex(ValueError, "direct-kernel"):
                factory.extract_source(archive, output)

    def test_release_catalog_pins_manifest_and_gzip_preserves_all_artifact_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in factory.ARTIFACTS.values():
                (root / name).write_bytes((name + "\n").encode() * 100)
            factory.write_release(root, "avf-debian-test", "https://example.invalid/avf-debian-test/", factory.SOURCE_URL, "a" * 64)
            manifest = dict(line.split("=", 1) for line in (root / "manifest.properties").read_text().splitlines())
            catalog = dict(line.split("=", 1) for line in (root / "catalog.properties").read_text().splitlines())
            self.assertEqual(factory.sha256(root / "manifest.properties"), catalog["manifestSha256"])
            self.assertEqual("gzip", catalog["compression"])
            self.assertEqual("1", manifest["aptInitializationVersion"])
            self.assertNotIn("virtiofsVersion", manifest)
            self.assertNotIn("androidStorageVersion", manifest)
            self.assertNotIn("shareBrokerVersion", manifest)
            self.assertEqual("1", manifest["sessionResumeVersion"])
            for key, name in factory.ARTIFACTS.items():
                self.assertEqual(manifest[key + ".sha256"], hashlib.sha256(gzip.decompress((root / (name + ".gz")).read_bytes())).hexdigest())
                self.assertLessEqual((root / name).stat().st_size, int(catalog["maxArtifactBytes"]))
            (root / "system.img").write_bytes(b"tampered")
            self.assertNotEqual(manifest["system.sha256"], factory.sha256(root / "system.img"))

    def test_catalog_does_not_allow_properties_injection_or_non_https(self):
        for value in ["http://example.invalid/release/", "https://user@example.invalid/release/",
                      "https://example.invalid/release/?latest=1", "https://example.invalid/release/\nid=evil"]:
            with self.assertRaises(ValueError):
                factory.validate_base_uri(value)

    def test_provenance_retains_source_commit_captured_before_production(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in factory.ARTIFACTS.values():
                (root / name).write_bytes(b"generated artifact")
            captured = "1" * 40
            with mock.patch.object(factory.subprocess, "check_output") as current_head:
                factory.write_release(root, "avf-debian-test", "https://example.invalid/avf-debian-test/",
                                      factory.SOURCE_URL, "a" * 64, source_commit=captured)
                current_head.assert_not_called()
            self.assertEqual(captured, json.loads((root / "provenance.json").read_text())["sourceCommit"])

    def test_insufficient_space_fails_before_docker_or_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = str(Path(temporary) / "release")
            with mock.patch('sys.argv', ['factory', '--release-id', 'test', '--base-uri', 'https://example.invalid/test/', '--output', output]), \
                    mock.patch.object(factory.shutil, 'disk_usage', return_value=mock.Mock(free=1024)), \
                    mock.patch.object(factory.subprocess, 'run') as docker, \
                    mock.patch.object(factory.urllib.request, 'urlopen') as network, \
                    mock.patch('sys.stderr', new_callable=io.StringIO):
                with self.assertRaises(SystemExit):
                    factory.main()
                docker.assert_not_called()
                network.assert_not_called()
            self.assertFalse(Path(output).exists())

    def test_guest_installer_resolves_the_service_executable_and_enables_units(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, binaries = Path(temporary) / "root", Path(temporary) / "bin"
            binaries.mkdir()
            for name in ["droid-sessiond", "droid-agent", "droid-port-agent", "droid"]:
                (binaries / name).write_text("#!/bin/sh\nexit 0\n")
                unit = REPO / f"guest/{name}/{name}.service"
                if unit.exists():
                    (binaries / (name + ".service")).write_bytes(unit.read_bytes())
            (binaries / "droid-apt-init.service").write_bytes((REPO / "guest/droid-sessiond/droid-apt-init.service").read_bytes())
            (binaries / "droid-file-transfer.service").write_bytes((REPO / "guest/droid-agent/droid-file-transfer.service").read_bytes())
            subprocess.run(["sh", str(REPO / "image/scripts/install-droid-guest.sh"), str(root), str(binaries)], check=True)
            for name in ["droid-sessiond", "droid-agent", "droid-port-agent", "droid-apt-init", "droid-file-transfer"]:
                unit = root / f"etc/systemd/system/{name}.service"
                executable = next(line.split("=", 1)[1].split()[0] for line in unit.read_text().splitlines() if line.startswith("ExecStart="))
                self.assertTrue((root / executable.lstrip("/")).is_file())
                self.assertTrue((root / f"etc/systemd/system/multi-user.target.wants/{name}.service").is_symlink())
            session_unit = (root / "etc/systemd/system/droid-sessiond.service").read_text()
            self.assertIn("User=droid\n", session_unit)
            self.assertIn("NoNewPrivileges=no\n", session_unit)
            self.assertIn("--package-initializer", (root / "etc/systemd/system/droid-apt-init.service").read_text())
            transfer_unit = (root / "etc/systemd/system/droid-file-transfer.service").read_text()
            self.assertIn("User=droid\n", transfer_unit)
            self.assertIn("--file-transfer", transfer_unit)
            self.assertIn("ProtectSystem=strict\n", transfer_unit)

    def test_smoke_requires_user_setup_success_on_each_boot(self):
        boots = [b"TERM_AVF_FIRST_BOOT_OK", b"TERM_AVF_PERSISTENCE_OK"]
        for failed_boot in range(2):
            results = [mock.Mock(returncode=0, stdout=marker + b"\nTERM_AVF_EXT4_ROOT_OK" + b"\nTERM_AVF_NETWORK_CONFIG_OK\nTERM_AVF_PACKAGE_BASE_CLEAN_OK\nTERM_AVF_PACKAGE_INIT_OK" +
                       (b"\nTERM_AVF_USER_SETUP_OK" if index != failed_boot else b""))
                       for index, marker in enumerate(boots)]
            with self.subTest(failed_boot=failed_boot), mock.patch('subprocess.run', side_effect=results), \
                    mock.patch('sys.stdout', new_callable=io.StringIO):
                with self.assertRaisesRegex(SystemExit, "user setup/package"):
                    runpy.run_path(str(REPO / "image/scripts/smoke-avf-image.py"))

    def test_guest_network_setup_replaces_builder_dns_and_preserves_other_configuration(self):
        for previous_resolver in ["file", "symlink"]:
            with self.subTest(previous_resolver=previous_resolver), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                for service in ["systemd-networkd", "systemd-resolved"]:
                    binary = root / f"usr/lib/systemd/{service}"
                    binary.parent.mkdir(parents=True, exist_ok=True)
                    binary.write_text("#!/bin/sh\nexit 0\n")
                    binary.chmod(0o755)
                    unit = root / f"usr/lib/systemd/system/{service}.service"
                    unit.parent.mkdir(parents=True, exist_ok=True)
                    unit.write_text(f"[Service]\nExecStart=/usr/lib/systemd/{service}\n"
                                    "[Install]\nWantedBy=multi-user.target\n")
                network_dir = root / "etc/systemd/network"
                network_dir.mkdir(parents=True)
                other = network_dir / "50-custom.network"
                other.write_text("[Match]\nName=custom0\n")
                resolver = root / "etc/resolv.conf"
                if previous_resolver == "file":
                    resolver.write_text("# Generated by Docker Engine.\nnameserver 169.254.169.254\n")
                else:
                    resolver.symlink_to("/run/missing-builder/resolv.conf")
                command = ["sh", str(REPO / "image/scripts/configure-avf-network.sh"), str(root),
                           str(REPO / "image/scripts/term-virtio.network")]
                for _ in range(2):
                    subprocess.run(command, check=True, capture_output=True, text=True)
                    self.assertEqual("/run/systemd/resolve/stub-resolv.conf", str(resolver.readlink()))
                    self.assertEqual((REPO / "image/scripts/term-virtio.network").read_bytes(),
                                     (network_dir / "10-term-virtio.network").read_bytes())
                    self.assertEqual(0o644, (network_dir / "10-term-virtio.network").stat().st_mode & 0o777)
                    self.assertEqual("[Match]\nName=custom0\n", other.read_text())
                    for service in ["systemd-networkd", "systemd-resolved"]:
                        subprocess.run(["systemctl", f"--root={root}", "is-enabled", "--quiet", service + ".service"],
                                       check=True, capture_output=True)

    def test_guest_network_setup_rejects_missing_resolver_before_changing_guest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            networkd = root / "usr/lib/systemd/systemd-networkd"
            networkd.parent.mkdir(parents=True)
            networkd.write_text("#!/bin/sh\nexit 0\n")
            networkd.chmod(0o755)
            (root / "etc").mkdir()
            resolver = root / "etc/resolv.conf"
            resolver.write_text("nameserver 169.254.169.254\n")
            result = subprocess.run(["sh", str(REPO / "image/scripts/configure-avf-network.sh"), str(root),
                                     str(REPO / "image/scripts/term-virtio.network")], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("systemd-resolved", result.stderr)
            self.assertEqual("nameserver 169.254.169.254\n", resolver.read_text())
            self.assertFalse((root / "etc/systemd").exists())

    def test_smoke_requires_sealed_network_configuration_on_each_boot(self):
        boots = [b"TERM_AVF_FIRST_BOOT_OK", b"TERM_AVF_PERSISTENCE_OK"]
        for failed_boot in range(2):
            results = [mock.Mock(returncode=0, stdout=marker + b"\nTERM_AVF_EXT4_ROOT_OK" + b"\nTERM_AVF_GUEST_PACKAGE_OK\nTERM_AVF_USER_SETUP_OK\nTERM_AVF_NO_SHARES_OK\nTERM_AVF_PACKAGE_BASE_CLEAN_OK\nTERM_AVF_PACKAGE_INIT_OK" +
                       (b"\nTERM_AVF_NETWORK_CONFIG_OK" if index != failed_boot else b""))
                       for index, marker in enumerate(boots)]
            with self.subTest(failed_boot=failed_boot), mock.patch('subprocess.run', side_effect=results), \
                    mock.patch('sys.stdout', new_callable=io.StringIO):
                with self.assertRaisesRegex(SystemExit, "network configuration"):
                    runpy.run_path(str(REPO / "image/scripts/smoke-avf-image.py"))

    def test_smoke_rejects_missing_sharing_retirement_evidence_on_either_boot(self):
        for missing in range(2):
            results = [mock.Mock(returncode=0, stdout=marker + b"\nTERM_AVF_EXT4_ROOT_OK" + b"\nTERM_AVF_GUEST_PACKAGE_OK\nTERM_AVF_USER_SETUP_OK\nTERM_AVF_NETWORK_CONFIG_OK\nTERM_AVF_PACKAGE_BASE_CLEAN_OK\nTERM_AVF_PACKAGE_INIT_OK" +
                                (b"\nTERM_AVF_NO_SHARES_OK" if index != missing else b""))
                       for index, marker in enumerate([b"TERM_AVF_FIRST_BOOT_OK", b"TERM_AVF_PERSISTENCE_OK"])]
            with self.subTest(missing=missing), mock.patch('subprocess.run', side_effect=results), mock.patch('sys.stdout', new_callable=io.StringIO):
                with self.assertRaisesRegex(SystemExit, "shared-directory retirement"):
                    runpy.run_path(str(REPO / "image/scripts/smoke-avf-image.py"))

    def test_smoke_accepts_both_offline_boots_only_after_user_setup_succeeds(self):
        results = [mock.Mock(returncode=0, stdout=marker + b"\nTERM_AVF_EXT4_ROOT_OK" + b"\nTERM_AVF_GUEST_PACKAGE_OK\nTERM_AVF_USER_SETUP_OK\nTERM_AVF_NO_SHARES_OK\nTERM_AVF_NETWORK_CONFIG_OK\nTERM_AVF_PACKAGE_BASE_CLEAN_OK\nTERM_AVF_PACKAGE_INIT_OK")
                   for marker in [b"TERM_AVF_FIRST_BOOT_OK", b"TERM_AVF_PERSISTENCE_OK"]]
        with mock.patch('subprocess.run', side_effect=results) as qemu, mock.patch('sys.stdout', new_callable=io.StringIO):
            runpy.run_path(str(REPO / "image/scripts/smoke-avf-image.py"))
        self.assertEqual(2, qemu.call_count)
        for invocation in qemu.call_args_list:
            command = invocation.args[0]
            self.assertEqual("none", command[command.index("-nic") + 1])
            self.assertIn("root=/dev/vdb rootfstype=ext4 rw", command[command.index("-append") + 1])

    def test_smoke_rejects_missing_ext4_root_evidence_on_either_boot(self):
        for missing in range(2):
            results = [mock.Mock(returncode=0, stdout=marker +
                b"\nTERM_AVF_GUEST_PACKAGE_OK\nTERM_AVF_USER_SETUP_OK\nTERM_AVF_NO_SHARES_OK\nTERM_AVF_NETWORK_CONFIG_OK\nTERM_AVF_PACKAGE_BASE_CLEAN_OK\nTERM_AVF_PACKAGE_INIT_OK" +
                (b"\nTERM_AVF_EXT4_ROOT_OK" if index != missing else b""))
                for index, marker in enumerate([b"TERM_AVF_FIRST_BOOT_OK", b"TERM_AVF_PERSISTENCE_OK"])]
            with self.subTest(missing=missing), mock.patch('subprocess.run', side_effect=results), mock.patch('sys.stdout', new_callable=io.StringIO):
                with self.assertRaisesRegex(SystemExit, "ext4 root"):
                    runpy.run_path(str(REPO / "image/scripts/smoke-avf-image.py"))

    def test_apt_cache_cleanup_preserves_release_trust_sources_and_package_database(self):
        def clean(root):
            subprocess.run(["sh", str(REPO / "image/scripts/clean-avf-apt.sh"), str(root)], check=True, capture_output=True)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            preserve = ["var/lib/apt/lists/debian_InRelease", "var/lib/apt/lists/debian_Release.gpg",
                        "var/lib/apt/lists/debian_Release", "var/lib/apt/lists/lock",
                        "var/lib/dpkg/status", "etc/apt/sources.list", "etc/apt/trusted.gpg"]
            remove = ["var/lib/apt/lists/debian_Packages.lz4", "var/lib/apt/lists/debian_Sources",
                      "var/lib/apt/lists/debian_Translation-en", "var/lib/apt/lists/partial/download",
                      "var/lib/apt/lists/auxfiles/stale", "var/cache/apt/archives/old.deb",
                      "var/cache/apt/archives/partial/old.deb", "var/cache/apt/pkgcache.bin",
                      "var/cache/apt/srcpkgcache.bin"]
            for name in preserve + remove:
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(name)
            partial = root / "var/lib/apt/lists/partial"
            partial.chmod(0o700)
            for _ in range(2):
                clean(root)
                for name in preserve:
                    self.assertEqual(name, (root / name).read_text())
                for name in remove:
                    self.assertFalse((root / name).exists())
                self.assertEqual(0o700, partial.stat().st_mode & 0o777)

    def test_apt_cache_cleanup_never_follows_links_outside_the_guest(self):
        def clean(root):
            return subprocess.run(["sh", str(REPO / "image/scripts/clean-avf-apt.sh"), str(root)], capture_output=True, text=True)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            outside = Path(temporary) / "outside"
            outside.mkdir()
            (outside / "keep").write_text("private")
            (root / "var/lib/apt").mkdir(parents=True)
            (root / "var/lib/apt/lists").symlink_to(outside, target_is_directory=True)
            result = clean(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("symlink", result.stderr)
            self.assertEqual("private", (outside / "keep").read_text())

    def test_smoke_rejects_missing_package_initialization_evidence(self):
        results = [mock.Mock(returncode=0, stdout=marker + b"\nTERM_AVF_EXT4_ROOT_OK" + b"\nTERM_AVF_USER_SETUP_OK\nTERM_AVF_NO_SHARES_OK\nTERM_AVF_NETWORK_CONFIG_OK\nTERM_AVF_PACKAGE_BASE_CLEAN_OK")
                   for marker in [b"TERM_AVF_FIRST_BOOT_OK", b"TERM_AVF_PERSISTENCE_OK"]]
        with mock.patch('subprocess.run', side_effect=results), mock.patch('sys.stdout', new_callable=io.StringIO):
            with self.assertRaisesRegex(SystemExit, "package initialization"):
                runpy.run_path(str(REPO / "image/scripts/smoke-avf-image.py"))


if __name__ == "__main__":
    unittest.main()
