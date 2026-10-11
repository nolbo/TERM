import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

PATH = Path(__file__).resolve().parents[2] / "tools/updates/verify-release-apk.py"
spec = importlib.util.spec_from_file_location("release_apk", PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseApkTest(unittest.TestCase):
    digest = "a" * 64

    def test_numbered_and_v31_sdk_range_signers_are_accepted(self):
        for signer in ("Signer #1", "Signer (minSdkVersion=31, maxSdkVersion=32)",
                       "Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647)"):
            output = f"{signer} certificate SHA-256 digest: {self.digest}\n"
            with self.subTest(signer=signer):
                self.assertEqual(module.verify_certificate(output, self.digest), self.digest)

    def test_same_identity_for_multiple_sdk_ranges_is_accepted(self):
        output = (f"Signer (minSdkVersion=31, maxSdkVersion=32) certificate SHA-256 digest: {self.digest}\n"
                  f"Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {self.digest.upper()}\n")
        self.assertEqual(module.verify_certificate(output, self.digest), self.digest)

    def test_unknown_missing_or_different_certificate_is_rejected(self):
        outputs = ("", f"Signer #1 certificate SHA-256 digest: {'b' * 64}\n",
                   f"Source Stamp Signer certificate SHA-256 digest: {self.digest}\n",
                   f"Signer #1 public key SHA-256 digest: {self.digest}\n")
        for output in outputs:
            with self.subTest(output=output), self.assertRaises(ValueError):
                module.verify_certificate(output, self.digest)

    def test_an_additional_unexpected_signer_is_rejected(self):
        output = (f"Signer #1 certificate SHA-256 digest: {self.digest}\n"
                  f"Signer #2 certificate SHA-256 digest: {'b' * 64}\n")
        with self.assertRaises(ValueError):
            module.verify_certificate(output, self.digest)

    def test_catalog_matches_exact_bytes_and_rejects_a_different_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("dev/terminal/image/approved-debian.properties", b"pinned catalog")
            module.verify_catalog(apk, b"pinned catalog")
            with self.assertRaises(ValueError):
                module.verify_catalog(apk, b"different pin")
