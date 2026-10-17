from concurrent.futures import ThreadPoolExecutor
import importlib.util
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

PATH = Path(__file__).resolve().parents[2] / "tools/updates/debug-version-code.py"
spec = importlib.util.spec_from_file_location("debug_version_code", PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DebugVersionCodeTest(unittest.TestCase):
    def test_clock_versions_exceed_legacy_local_and_ci_versions(self):
        october_2026 = 1_792_195_200
        code = module.debug_version_code(october_2026, minimum=1_000_070)
        self.assertGreater(code, 1_000_070)
        self.assertGreater(module.debug_version_code(october_2026 + 1), code)

    def test_repeated_builds_and_clock_rollback_advance_the_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "debug.sqlite3"
            self.assertEqual(module.reserve_debug_version(state, timestamp=module.EPOCH + 100), 100)
            self.assertEqual(module.reserve_debug_version(state, timestamp=module.EPOCH + 100), 101)
            self.assertEqual(module.reserve_debug_version(state, timestamp=module.EPOCH - 1), 102)

    def test_concurrent_builds_reserve_distinct_increasing_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "debug.sqlite3"
            with ThreadPoolExecutor(max_workers=8) as pool:
                codes = list(pool.map(lambda _: module.reserve_debug_version(
                    state, timestamp=module.EPOCH + 100), range(16)))
            self.assertEqual(sorted(codes), list(range(100, 116)))

    def test_installed_version_floor_is_exceeded(self):
        self.assertEqual(module.debug_version_code(module.EPOCH + 100, minimum=500), 501)
        self.assertEqual(module.debug_version_code(module.EPOCH + 100, previous=700, minimum=500), 701)

    def test_rejects_invalid_numbers_and_uninitialized_old_clock(self):
        for invalid in (True, False, -1, "-1", "", " 1", "1.0", "١", "1+2"):
            for position in range(3):
                values = [module.EPOCH + 100, 0, 0]
                values[position] = invalid
                with self.subTest(value=invalid, position=position), self.assertRaises(ValueError):
                    module.debug_version_code(*values)
        with self.assertRaises(ValueError):
            module.debug_version_code(module.EPOCH - 1)

    def test_overflow_fails_without_resetting_the_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "debug.sqlite3"
            self.assertEqual(module.reserve_debug_version(state, minimum=module.MAX_VERSION_CODE - 1),
                             module.MAX_VERSION_CODE)
            with self.assertRaises(ValueError):
                module.reserve_debug_version(state)
            with sqlite3.connect(state) as db:
                self.assertEqual(db.execute("SELECT code FROM version").fetchone()[0], module.MAX_VERSION_CODE)

    def test_cli_fails_closed_on_corrupt_state(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "debug.sqlite3"
            state.write_text("corrupt ledger")
            result = subprocess.run(["python3", str(PATH), "--state-file", str(state)],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")
            self.assertEqual(state.read_text(), "corrupt ledger")

    def test_cli_persists_versions_across_invocations(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "debug.sqlite3"
            command = ["python3", str(PATH), "--state-file", str(state)]
            first = subprocess.run(command, check=True, capture_output=True, text=True)
            second = subprocess.run(command, check=True, capture_output=True, text=True)
            self.assertGreater(int(second.stdout), int(first.stdout))
