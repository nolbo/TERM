import importlib.util
import os
from pathlib import Path
import subprocess
import unittest

PATH = Path(__file__).resolve().parents[2] / "tools/updates/version-code.py"
spec = importlib.util.spec_from_file_location("version_code", PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VersionCodeTest(unittest.TestCase):
    def test_migration_continues_after_last_published_code(self):
        previous = module.version_code(70)
        self.assertEqual(previous, 1_000_070)
        self.assertEqual(module.version_code(1, 70), previous + 1)
        self.assertEqual(module.version_code(2, 70), previous + 2)
        self.assertEqual(module.version_code(1, 70), previous + 1)  # Retry.

    def test_review_without_an_android_run_uses_the_previous_baseline(self):
        self.assertEqual(module.version_code(0, 70), 1_000_070)

    def test_rejects_invalid_sequence_inputs_and_overflow(self):
        for invalid in (True, False, -1, "-1", "", " 1", "1.0", "١", "1+2"):
            with self.subTest(value=invalid):
                with self.assertRaises(ValueError):
                    module.version_code(invalid, 70)
                with self.assertRaises(ValueError):
                    module.version_code(1, invalid)
        with self.assertRaises(ValueError):
            module.version_code(module.MAX_VERSION_CODE - module.EPOCH + 1)
        self.assertEqual(module.version_code(module.MAX_VERSION_CODE - module.EPOCH), module.MAX_VERSION_CODE)

    def test_cli_reads_the_repository_offset_and_fails_closed(self):
        env = dict(os.environ, TERMINAL_VERSION_CODE_OFFSET="70")
        result = subprocess.run(["python3", str(PATH), "--run-number", "1"], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "1000071")
        env["TERMINAL_VERSION_CODE_OFFSET"] = "invalid"
        result = subprocess.run(["python3", str(PATH), "--run-number", "1"], env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
