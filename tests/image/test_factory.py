import gzip
import hashlib
import importlib.util
import io
from pathlib import Path
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
            subprocess.run(["sh", str(REPO / "image/scripts/install-droid-guest.sh"), str(root), str(binaries)], check=True)
            for name in ["droid-sessiond", "droid-agent", "droid-port-agent"]:
                unit = root / f"etc/systemd/system/{name}.service"
                executable = next(line.split("=", 1)[1] for line in unit.read_text().splitlines() if line.startswith("ExecStart="))
                self.assertTrue((root / executable.lstrip("/")).is_file())
                self.assertTrue((root / f"etc/systemd/system/multi-user.target.wants/{name}.service").is_symlink())


if __name__ == "__main__":
    unittest.main()
