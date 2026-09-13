import tempfile
from pathlib import Path
import unittest
import sys
import fcntl
import io
from unittest.mock import patch
import safety_gate as gate


class SafetyGateTest(unittest.TestCase):
    def test_skips_empty_and_failure_are_not_pass(self):
        self.assertEqual("INCOMPLETE", gate.classify(0, 0, 0))
        self.assertEqual("INCOMPLETE", gate.classify(3, 0, 1))
        self.assertEqual("FAILED", gate.classify(3, 1, 1))
        self.assertEqual("PASSED", gate.classify(3, 0, 0))

    def test_raw_logs_are_not_reported_and_missing_summary_is_incomplete(self):
        self.assertEqual({"status": "INCOMPLETE"}, gate.text_result("rust", "PASSED", "private-sentinel"))
        self.assertEqual("INCOMPLETE", gate.text_result("python", "PASSED", "Ran 42 tests\nOK (skipped=4)")["status"])
        self.assertEqual("FAILED", gate.text_result("python", "FAILED", "Ran 42 tests\nFAILED")["status"])
        self.assertEqual("PASSED", gate.text_result("rust", "PASSED", "test result: ok. 14 passed; 0 failed; 0 ignored")["status"])

    def test_automated_success_never_certifies_hardware(self):
        result = gate.report({"python": {"status": "PASSED"}}, "revision")
        self.assertEqual("PASSED", result["automatedScope"])
        self.assertEqual("NOT_VALIDATED", result["physicalDeviceSafety"])
        self.assertEqual("NOT_RUN", result["androidInstrumentation"])
        self.assertEqual("INCOMPLETE", gate.report({}, "revision")["automatedScope"])

    def test_missing_junit_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual("INCOMPLETE", gate.junit_result(Path(directory))["status"])
            path = Path(directory) / "TEST-safety.xml"
            path.write_text('<testsuite tests="2" failures="0" errors="0" skipped="1"/>')
            self.assertEqual("INCOMPLETE", gate.junit_result(Path(directory))["status"])
            path.write_text('<testsuite tests="2" failures="1" errors="0" skipped="0"/>')
            self.assertEqual("FAILED", gate.junit_result(Path(directory))["status"])
            path.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
            self.assertEqual("PASSED", gate.junit_result(Path(directory))["status"])
            path.write_text('truncated')
            self.assertEqual("INCOMPLETE", gate.junit_result(Path(directory))["status"])

    def test_process_failure_missing_executable_and_deadline_fail_closed(self):
        self.assertEqual("FAILED", gate.execute([sys.executable, "-c", "raise SystemExit(1)"])[0])
        self.assertEqual("INCOMPLETE", gate.execute(["/nonexistent/terminal-safety-test"])[0])
        self.assertEqual("INCOMPLETE", gate.execute([sys.executable, "-c", "import time; time.sleep(10)"], 0.05)[0])

    def test_concurrent_runner_is_rejected_before_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".gradle").mkdir()
            with (root / ".gradle/usb-safety.lock").open("a") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with patch.object(gate, "ROOT", root), patch.object(sys, "argv", ["safety_gate.py"]), \
                        patch.object(gate.subprocess, "check_output", return_value="revision"), \
                        patch.object(gate, "execute") as execute, patch("sys.stdout", new=io.StringIO()) as output:
                    self.assertEqual(2, gate.main())
                    self.assertIn("RUNNER_BUSY", output.getvalue())
                    execute.assert_not_called()
