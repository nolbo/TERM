import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("create_manifest", Path(__file__).resolve().parents[2] / "tools/updates/create-manifest.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "android-app-release.apk"
        self.payload = b"built APK payload"
        self.apk.write_bytes(self.payload)
        self.commit = "1" * 40
        self.metadata = dict(applicationId="dev.terminal.app", minSdkVersionForDexing=31, elements=[dict(
            versionCode=32, versionName=f"0.1.0+main.32.{self.commit[:12]}", outputFile=self.apk.name)])

    def test_hash_and_version_come_from_the_selected_built_apk(self):
        result = module.create_manifest(self.apk, self.metadata, self.commit)
        self.assertEqual(result["size"], len(self.payload))
        self.assertEqual(result["sha256"], hashlib.sha256(self.payload).hexdigest())
        self.assertEqual(result["commit"], self.commit)
        self.assertEqual(result["versionCode"], 32)
        self.assertEqual(result["minSdk"], 31)
        self.assertEqual(result["applicationId"], "dev.terminal.app")

    def test_cannot_publish_an_apk_versioned_from_a_different_main_commit(self):
        with self.assertRaises(ValueError):
            module.create_manifest(self.apk, self.metadata, "2" * 40)

    def test_rejects_wrong_app_multiple_outputs_and_invalid_versions(self):
        for value in [False, 0, -1, 32.5, "32", 2147483648]:
            metadata = copy.deepcopy(self.metadata)
            metadata["elements"][0]["versionCode"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.create_manifest(self.apk, metadata, self.commit)
        metadata = copy.deepcopy(self.metadata)
        metadata["applicationId"] = "another.app"
        with self.assertRaises(ValueError):
            module.create_manifest(self.apk, metadata, self.commit)
        metadata = copy.deepcopy(self.metadata)
        metadata["elements"] *= 2
        with self.assertRaises(ValueError):
            module.create_manifest(self.apk, metadata, self.commit)

    def test_rejects_the_wrong_file_and_empty_or_oversized_apks(self):
        metadata = copy.deepcopy(self.metadata)
        metadata["elements"][0]["outputFile"] = "different.apk"
        with self.assertRaises(ValueError):
            module.create_manifest(self.apk, metadata, self.commit)
        for size in [0, module.MAX_APK_BYTES + 1]:
            with self.apk.open("wb") as stream:
                stream.truncate(size)
            with self.subTest(size=size), self.assertRaises(ValueError):
                module.create_manifest(self.apk, self.metadata, self.commit)

    def test_dev_release_is_bound_to_the_selected_channel(self):
        metadata = copy.deepcopy(self.metadata)
        metadata["applicationId"] = "dev.terminal.app.prerelease"
        metadata["elements"][0]["versionName"] = f"0.1.0+dev.32.{self.commit[:12]}"
        result = module.create_manifest(self.apk, metadata, self.commit, "dev")
        self.assertEqual(result["channel"], "dev")
        self.assertEqual(result["applicationId"], "dev.terminal.app.prerelease")
        with self.assertRaises(ValueError):
            module.create_manifest(self.apk, metadata, self.commit)
        with self.assertRaises(ValueError):
            module.create_manifest(self.apk, self.metadata, self.commit, "dev")
        with self.assertRaises(ValueError):
            module.create_manifest(self.apk, metadata, self.commit, "other")

    def test_channel_cannot_publish_the_other_app_debug_or_a_legacy_dev_package(self):
        for channel, invalid_ids in {
            "main": ("dev.terminal.app.prerelease", "dev.terminal.app.debug"),
            "dev": ("dev.terminal.app", "dev.terminal.app.debug"),
        }.items():
            metadata = copy.deepcopy(self.metadata)
            metadata["elements"][0]["versionName"] = f"0.1.0+{channel}.32.{self.commit[:12]}"
            for application_id in invalid_ids:
                metadata["applicationId"] = application_id
                with self.subTest(channel=channel, application_id=application_id), self.assertRaises(ValueError):
                    module.create_manifest(self.apk, metadata, self.commit, channel)

    def test_correct_package_still_requires_matching_version_channel(self):
        for channel, application_id, wrong_channel in (
            ("main", "dev.terminal.app", "dev"),
            ("dev", "dev.terminal.app.prerelease", "main"),
        ):
            metadata = copy.deepcopy(self.metadata)
            metadata["applicationId"] = application_id
            metadata["elements"][0]["versionName"] = f"0.1.0+{wrong_channel}.32.{self.commit[:12]}"
            with self.subTest(channel=channel), self.assertRaises(ValueError):
                module.create_manifest(self.apk, metadata, self.commit, channel)


if __name__ == "__main__":
    unittest.main()
