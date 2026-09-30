#!/usr/bin/env python3
"""Functional regressions, including exit-0/wrong-output and cancellation."""
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

import common
import fixtures
import run as driver
import worker


@unittest.skipUnless(shutil.which("git") and shutil.which("rg"), "installed git and rg are required")
class Workloads(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="rootless-harness-tests-")
        cls.root = Path(cls.temporary.name)
        cls.bundle = cls.root / "bundle"
        fixtures.prepare(cls.bundle, counts=(24,), package_counts=(3,))
        cls.serial = 0

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def directory(self):
        type(self).serial += 1
        path = self.root / f"run-{self.serial}"
        path.mkdir()
        return path

    def job(self, case, **extra):
        return {"case": case, "bundle": str(self.bundle), "work": str(self.directory()),
                "repository": "synthetic-24", **extra}

    def test_real_tools_and_incremental_replay(self):
        result = worker.run_job(self.job("local-replay"))
        self.assertEqual(result["status"], "passed", result.get("reason"))
        self.assertEqual(result["verified"]["tests"], 3)
        self.assertEqual(result["verified"]["incremental_sources_rebuilt"], ["calc.py"])
        self.assertGreater(result["preparation_ms"], 0)
        self.assertTrue(all(phase.get("stdout_sha256") for phase in result["phases"] if "argv" in phase))

    def test_only_fixture_git_objects_are_packed_for_transport(self):
        with tarfile.open(self.bundle / "synthetic-24.tar") as archive:
            names = [entry.name.removeprefix("./") for entry in archive]
        self.assertTrue(any(name.startswith(".git/objects/pack/") and name.endswith(".pack") for name in names))
        self.assertFalse(any(__import__("re").fullmatch(r"\.git/objects/[0-9a-f]{2}/[0-9a-f]+", name) for name in names))

    def test_two_independent_workspaces_and_cancel_reap(self):
        result = worker.run_job(self.job("two-workspaces"))
        self.assertEqual(result["status"], "passed", result.get("reason"))
        self.assertEqual(len(result["verified"]["sessions"]), 2)
        cancellation = result["verified"]["cancellation"]
        self.assertTrue(cancellation["cancelled_and_reaped"])
        self.assertEqual(cancellation["fd_before"], cancellation["fd_after"])

    def test_wrong_output_with_successful_exit_is_failure(self):
        malicious = self.directory()
        (malicious / "rg").write_text("#!/bin/sh\nprintf 'silently wrong output\\n'\nexit 0\n")
        (malicious / "rg").chmod(0o755)
        with mock.patch.dict(os.environ, PATH=str(malicious) + os.pathsep + os.environ["PATH"]):
            result = worker.run_job(self.job("repository"))
        self.assertEqual(result["status"], "failed")
        self.assertIn("output differs", result["reason"])
        phase = next(value for value in result["phases"] if value["name"] == "rg-list")
        self.assertEqual(phase["exit_code"], 0)

    def test_zero_exit_without_guest_result_is_failure(self):
        directory = self.directory() / "launcher"
        profile = {"kind": "native-control", "command": [sys.executable, "-c", "print('no guest result')"]}
        result = driver.one_run(profile, self.bundle, directory, "repository:synthetic-24")
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["launcher"]["exit_code"], 0)
        self.assertIn("structured result", result["reason"])

    def test_missing_repo_and_authentication_are_skips(self):
        absent = worker.run_job(self.job("repository", repository="pinned"))
        self.assertEqual(absent["status"], "skipped")
        unauthenticated = worker.run_job(self.job("agent-run"))
        self.assertEqual(unauthenticated["status"], "skipped")
        self.assertIn("authenticated-run", unauthenticated["reason"])

    def test_agent_config_rejected_before_serializing_inline_credentials(self):
        directory = self.directory() / "rejected"
        with self.assertRaises(common.CheckFailure):
            driver.one_run({"command": [sys.executable]}, self.bundle, directory, "agent-run",
                           agent={"environment": {"UNUSED_TEST_KEY": "not-a-real-credential"}}, enable_agent=True)
        self.assertFalse(directory.exists())

    def test_mock_agent_ignored_file_change_fails_scope_check(self):
        # A local Python stand-in exercises verification, never a CLI/model or
        # authentication. Correct output alone must not hide unrelated edits.
        fixed = "def total(values):\n    return sum(values)\n"
        script = ("from pathlib import Path\n"
                  f"Path('bench_project/calc.py').write_text({fixed!r})\n"
                  "p=Path('bench_project/build');p.mkdir();(p/'unexpected').write_text('out of scope')\n"
                  "print('{\"type\":\"mock-complete\"}')\n")
        agent = {"name": "codex", "argv": [sys.executable, "-B", "-c", script],
                 "expected_files": {"bench_project/calc.py": common.digest(fixed.encode())},
                 "verify_argv": [sys.executable, "-c", "print('unused')"],
                 "verify_stdout_sha256": common.digest(b"unused\n")}
        result = worker.run_job(self.job("agent-run", enable_authenticated_run=True, agent=agent))
        self.assertEqual(result["status"], "failed")
        self.assertIn("outside declared task scope", result["reason"])

    def test_timeout_even_when_both_output_streams_closed(self):
        directory = self.directory()
        result = common.execute([sys.executable, "-c", "import os,time;os.close(1);os.close(2);time.sleep(30)"],
                                directory, timeout=0.15)
        self.assertTrue(result["timed_out"])
        self.assertLess(result["wall_ms"], 3000)

    def test_archive_escape_rejected_before_write(self):
        directory = self.directory()
        archive = directory / "escape.tar"
        with tarfile.open(archive, "w") as output:
            info = tarfile.TarInfo("../escaped")
            info.size = 3
            output.addfile(info, io.BytesIO(b"bad"))
        target = directory / "target"
        target.mkdir()
        with self.assertRaises(common.CheckFailure):
            fixtures.safe_extract(archive, target)
        self.assertFalse((directory / "escaped").exists())

    def test_counter_parsing_keeps_scope_and_process_reports(self):
        result = common.parse_counters('proot-perf: enter_stop=5 exit_stop=3 vm_write=7\n'
            'noise\n{"faststat":{"hits":9}}\nproot-perf: enter_stop=2 exit_stop=1 vm_write=4\n')
        self.assertEqual(result["proot_totals"], {"enter_stop": 7, "exit_stop": 4, "vm_write": 11})
        self.assertEqual(len(result["reports"]), 3)
        self.assertIn("preparation", result["scope"])

    def test_pair_statistics_do_not_hide_failed_output(self):
        rows = []
        for index in range(10):
            for mode, value in (("baseline", 100 + index), ("candidate", 80 + index)):
                rows.append({"case_key": "replay", "mode": mode, "pair": index,
                    "result": {"status": "passed", "measured_ms": value,
                               "workflow_elapsed_ms": value + 40, "phases": []}})
        result = driver.summarize(rows, 10, True)["replay"]
        self.assertTrue(result["comparison_eligible"])
        self.assertEqual(result["paired_absolute_savings_ms"]["median_ms"], 20)
        self.assertEqual(result["measured_ms"]["baseline"]["p95_ms"], 109)
        rows[-1]["result"]["status"] = "failed"
        result = driver.summarize(rows, 10, True)["replay"]
        self.assertFalse(result["comparison_eligible"])
        self.assertNotIn("median_time_reduction_percent", result)

    @unittest.skipUnless(os.environ.get("ROOTLESS_WORKLOAD_PACKAGE_TEST") == "1" and shutil.which("fakeroot"),
                         "set ROOTLESS_WORKLOAD_PACKAGE_TEST=1 for explicit installed-fakeroot package controls")
    def test_actual_dpkg_and_apt_cycles(self):
        for case in ("dpkg-3", "apt-3"):
            job = self.job(case)
            path = Path(job["work"]) / "job.json"
            common.write_json(path, job)
            completed = subprocess.run(["fakeroot", "--", sys.executable, str(self.bundle / "worker.py"), "--job", str(path)],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
            self.assertEqual(completed.returncode, 0, completed.stdout.decode() + completed.stderr.decode())
            result = json.loads(completed.stdout)["rootless_workload"]
            self.assertEqual(result["status"], "passed", result)
            self.assertTrue(result["verified"]["removed"])
            self.assertEqual([entry["version"] for entry in result["verified"]["installed_versions"]], [1, 2])


if __name__ == "__main__":
    unittest.main()
