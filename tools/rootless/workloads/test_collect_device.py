#!/usr/bin/env python3
"""Device collector protocol, incomplete-run and path-disclosure regressions."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("collect_device", Path(__file__).with_name("collect-device.py"))
collector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collector)


class DeviceCollector(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="rootless-device-collector-")
        self.root = Path(self.temporary.name)
        self.cases = collector.cases_from(["pkg=dpkg-1000"])
        (self.root / "android-api.txt").write_text("36\n")
        (self.root / "selinux.txt").write_text("Enforcing\n")
        (self.root / "inputs.sha256").write_text("".join(
            f"{index:064x}  /data/app/PRIVATE_RANDOM_APK/lib/arm64/{name}\n"
            for index, name in enumerate(collector.ARTIFACTS, 1)))
        (self.root / "done").write_text("DONE\n")

    def tearDown(self):
        self.temporary.cleanup()

    def payload(self, measured=100):
        return {"schema": 1, "status": "passed", "case": "dpkg-1000", "measurement_kind": "timing",
                "measured_ms": measured, "workflow_elapsed_ms": measured + 20, "preparation_ms": 10,
                "verification_ms": 10, "first_measured_stdout_ms": 2,
                "facts": {"architecture": "aarch64", "uid": 10123, "page_size": 4096,
                          "secret": "/data/user/0/private.app/cache/PRIVATE_CACHE"},
                "verified": {"removed": True, "count": 1000},
                "phases": [{"name": "install", "measured": True, "wall_ms": measured,
                            "exit_code": 0, "timed_out": False, "first_stdout_ms": 2,
                            "argv": ["/data/app/PRIVATE_RANDOM_APK/libproot.so"],
                            "stdout_file": "/data/user/0/private.app/cache/PRIVATE_CACHE/logs/tool.stdout"}]}

    def batch(self, pairs=10):
        statuses = []
        for pair in range(1, pairs + 1):
            for mode in (("baseline", "candidate") if pair % 2 else ("candidate", "baseline")):
                value = self.payload((100 if mode == "baseline" else 80) + pair - 1)
                (self.root / f"{pair}-pkg-{mode}.log").write_text(
                    'proot warning: harmless fixture message\n' + json.dumps({"rootless_workload": value}) + "\n")
                statuses.append(f"{pair}\tpkg\t{mode}\t0\n")
        (self.root / "status.tsv").write_text("".join(statuses))

    def collect(self, pairs=10, smoke=False):
        return collector.collect(self.root, pairs, self.cases, "disabled", smoke)

    def test_verified_ten_pair_summary_and_private_path_allowlist(self):
        self.batch()
        result = self.collect()
        self.assertEqual(result["status"], "completed")
        summary = result["summaries"]["dpkg-1000"]
        self.assertTrue(summary["comparison_eligible"])
        self.assertEqual(summary["measured_ms"]["baseline"]["median_ms"], 104.5)
        self.assertEqual(summary["measured_ms"]["candidate"]["p95_ms"], 89)
        self.assertEqual(summary["paired_absolute_savings_ms"]["median_ms"], 20)
        self.assertEqual(sum(row["first_invocation_for_case"] for row in result["rows"]), 2)
        self.assertEqual(result["platform"], {"android_api": 36, "selinux": "Enforcing"})
        self.assertEqual(result["actual_agent"]["status"], "skipped")
        serialized = json.dumps(result)
        for forbidden in ("/data/", "PRIVATE_", "private.app", '"argv"', '"uid"'):
            self.assertNotIn(forbidden, serialized)
        self.assertEqual(set(result["input_sha256"]), set(collector.ARTIFACTS))

    def test_metadata_cli_writes_reviewable_summary(self):
        self.batch()
        metadata = self.root / "metadata.json"
        metadata.write_text(json.dumps({"schema": 1, "pairs": 10, "cases": self.cases,
                                        "timing_instrumentation": "disabled", "smoke": False}))
        output = self.root / "summary.json"
        process = subprocess.run([sys.executable, str(Path(__file__).with_name("collect-device.py")),
            "--input", str(self.root), "--output", str(output), "--metadata", str(metadata)],
            capture_output=True, text=True, timeout=10)
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(json.loads(process.stdout), {"status": "completed", "attempts": 20, "eligible_cases": 1})
        self.assertEqual(json.loads(output.read_text())["status"], "completed")

    def test_numeric_resources_and_output_hashes_survive_without_paths_or_invented_samples(self):
        self.batch()
        value = self.payload()
        value["phases"][0].update(user_cpu_ms=12.5, system_cpu_ms=3.0, peak_rss_kib=1234,
            voluntary_context_switches=7, involuntary_context_switches=None,
            cpu_scope="PRIVATE_HOST", stdout_bytes=17, stdout_sha256="d" * 64)
        (self.root / "1-pkg-baseline.log").write_text(json.dumps({"rootless_workload": value}))
        result = self.collect()
        self.assertEqual(result["status"], "completed")
        phase = result["rows"][0]["result"]["phases"][0]
        self.assertEqual(phase["user_cpu_ms"], 12.5)
        self.assertEqual(phase["peak_rss_kib"], 1234)
        self.assertIsNone(phase["involuntary_context_switches"])
        self.assertEqual(phase["stdout_sha256"], "d" * 64)
        metric = result["summaries"]["dpkg-1000"]["phase_resources"]["install"]["user_cpu_ms"]
        self.assertEqual(metric["baseline"]["median"], 12.5)
        self.assertEqual(metric["baseline"]["unit"], "ms")
        rss = result["summaries"]["dpkg-1000"]["phase_resources"]["install"]["peak_rss_kib"]
        self.assertEqual(rss["baseline"]["unit"], "KiB")
        self.assertEqual(rss["baseline"]["median"], 1234)
        self.assertEqual(metric["baseline"]["unavailable_samples"], 9)
        self.assertEqual(metric["candidate"]["unavailable_samples"], 10)
        self.assertNotIn("PRIVATE_", json.dumps(result))

    def test_invalid_resources_disqualify_the_comparison(self):
        for invalid in (True, -1, float("nan"), "PRIVATE_HOST"):
            self.batch()
            value = self.payload()
            value["phases"][0]["user_cpu_ms"] = invalid
            (self.root / "1-pkg-baseline.log").write_text(json.dumps({"rootless_workload": value}))
            result = self.collect()
            self.assertEqual(result["status"], "failed")
            self.assertFalse(result["summaries"]["dpkg-1000"]["comparison_eligible"])
            self.assertIn("invalid_phase_resource", result["rows"][0]["result"]["reason_codes"])

    def test_tool_and_platform_metadata_preserved_without_private_identifiers(self):
        self.batch()
        path = self.root / "1-pkg-baseline.log"
        value = self.payload()
        elf = {"status": "present", "sha256": "a" * 64, "format": "ELF", "elf_machine": 183,
               "linkage": "dynamic", "interpreter": "/lib/ld-linux-aarch64.so.1"}
        value["facts"].update(
            kernel_release="6.1.99-android14-11-gabcdef012345-ab12345678-4k", python="3.12.3",
            distribution={"ID": "ubuntu", "VERSION_ID": "24.04", "HOSTNAME": "PRIVATE_HOST"},
            hostname="PRIVATE_HOST", serial="PRIVATE_SERIAL", env={"TOKEN": "PRIVATE_CREDENTIAL"},
            tools={"git": {**elf, "version": ["git version 2.43.0"]},
                   "rg": {**elf, "version": ["ripgrep 14.1.0"], "interpreter": "/private/cache/PRIVATE_LOADER"},
                   "dpkg": {**elf, "version": ["Debian 'dpkg' package management program version 1.22.6 (arm64)."]},
                   "apt-get": {**elf, "version": ["apt 2.7.14 (arm64)"]},
                   "python3": {**elf, "version": ["Python 3.12.3 PRIVATE_HOST"]},
                   "PRIVATE_TOOL": elf})
        path.write_text(json.dumps({"rootless_workload": value}))
        result = self.collect()
        self.assertTrue(result["summaries"]["dpkg-1000"]["comparison_eligible"])
        facts = result["rows"][0]["result"]["guest_facts"]
        self.assertEqual(facts["distribution"], {"ID": "ubuntu", "VERSION_ID": "24.04"})
        self.assertEqual(facts["kernel_release"], value["facts"]["kernel_release"])
        self.assertEqual(facts["tools"]["git"]["version"], "2.43.0")
        self.assertEqual(facts["tools"]["git"]["sha256"], "a" * 64)
        self.assertEqual(facts["tools"]["git"]["interpreter"], "/lib/ld-linux-aarch64.so.1")
        self.assertEqual(facts["tools"]["rg"]["version"], "14.1.0")
        self.assertEqual(facts["tools"]["rg"]["interpreter_status"], "omitted_nonallowlisted_path")
        self.assertEqual(facts["tools"]["dpkg"]["version"], "1.22.6")
        self.assertEqual(facts["tools"]["apt-get"]["version"], "2.7.14")
        self.assertNotIn("version", facts["tools"]["python3"])
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertNotIn("/private", json.dumps(result))
        filtered = collector.guest_metadata({"kernel_release": "6.1.99-PRIVATE_HOST-android14", "uid": 10123})
        self.assertEqual(filtered, {"kernel_release": "6.1.99", "kernel_release_scope": "recognized_version_prefix_only", "tools": {}})

    def test_cli_binary_metadata_and_version_hash_do_not_invent_version_or_auth_support(self):
        self.cases = collector.cases_from(["cli=cli-probe"])
        statuses = []
        for pair in range(1, 11):
            for mode in (("baseline", "candidate") if pair % 2 else ("candidate", "baseline")):
                value = self.payload()
                value["case"] = "cli-probe"
                value["phases"] = [dict(value["phases"][0], name="codex--version", stdout_bytes=17,
                                        stdout_sha256="b" * 64, stdout_file="/private/cache/PRIVATE_STDOUT")]
                value["verified"] = {
                    "codex": {"status": "passed", "executable": {"sha256": "c" * 64, "format": "ELF",
                              "linkage": "static", "elf_machine": 183, "interpreter": None,
                              "path": "/private/cache/PRIVATE_BINARY"}, "phases": value["phases"]},
                    "claude": {"status": "skipped", "reason": "/private/cache/PRIVATE_REASON"}}
                (self.root / f"{pair}-cli-{mode}.log").write_text(json.dumps({"rootless_workload": value}))
                statuses.append(f"{pair}\tcli\t{mode}\t0\n")
        (self.root / "status.tsv").write_text("".join(statuses))
        result = self.collect()
        support = result["rows"][0]["result"]["cli_support"]
        self.assertEqual(result["status"], "completed")
        self.assertFalse(result["summaries"]["cli-probe"]["comparison_eligible"])
        self.assertEqual(support["codex"]["executable"]["linkage"], "static")
        self.assertEqual(support["codex"]["executable"]["sha256"], "c" * 64)
        self.assertEqual(support["codex"]["version_status"], "not_recorded_in_worker_envelope")
        self.assertEqual(support["codex"]["version_probe"]["stdout_sha256"], "b" * 64)
        self.assertEqual(support["claude"]["status"], "skipped")
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertNotIn("/private", json.dumps(result))
        self.assertEqual(result["actual_agent"]["status"], "skipped")
        declared = collector.cli_metadata({"codex": {"status": "passed", "version": "codex-cli 0.159.2"},
                                           "claude": {"status": "passed", "version": "2.0.10 (Claude Code)"}})
        self.assertEqual(declared["codex"]["version"], "0.159.2")
        self.assertEqual(declared["claude"]["version"], "2.0.10")

    def test_exit_zero_without_worker_envelope_is_preserved_failure(self):
        self.batch()
        (self.root / "1-pkg-baseline.log").write_text("proot exited zero without running guest\n")
        result = self.collect()
        self.assertEqual(result["status"], "failed")
        row = result["rows"][0]
        self.assertEqual(row["real_exit"], 0)
        self.assertIn("expected_exactly_one_worker_envelope", row["result"]["reason_codes"])
        self.assertFalse(result["summaries"]["dpkg-1000"]["comparison_eligible"])
        self.assertNotIn("median_time_reduction_percent", result["summaries"]["dpkg-1000"])

    def test_missing_expected_pairs_and_done_are_not_inferred_away(self):
        self.batch(pairs=9)
        (self.root / "done").unlink()
        result = self.collect(pairs=10)
        self.assertEqual(len(result["rows"]), 20)
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["summaries"]["dpkg-1000"]["status_counts"]["baseline"]["failed"], 1)
        self.assertIn("missing_status_row", result["rows"][-1]["result"]["reason_codes"])
        self.assertFalse(result["batch_done"])

    def test_failed_baseline_not_averaged_or_treated_as_zero_time(self):
        self.batch()
        payload = self.payload(0)
        payload.update(status="failed", reason="exit -11 from /private/cache/tool")
        (self.root / "1-pkg-baseline.log").write_text(json.dumps({"rootless_workload": payload}) + "\n")
        status = self.root / "status.tsv"
        status.write_text(status.read_text().replace("1\tpkg\tbaseline\t0\n", "1\tpkg\tbaseline\t1\n", 1))
        result = self.collect()
        summary = result["summaries"]["dpkg-1000"]
        self.assertEqual(summary["measured_ms"]["baseline"]["count"], 9)
        self.assertNotIn("paired_absolute_savings_ms", summary)
        self.assertNotIn("/private", json.dumps(result))

    def test_guest_signal_exit_disagreement_and_duplicate_envelopes_fail(self):
        self.batch(pairs=1)
        path = self.root / "1-pkg-baseline.log"
        original = path.read_text()
        for raw in ("proot info: guest terminated with signal 11\n" + original, original + original):
            with self.subTest(raw=raw[:40]):
                path.write_text(raw)
                self.assertEqual(self.collect(1, True)["rows"][0]["result"]["status"], "failed")
        path.write_text(original)
        status = self.root / "status.tsv"
        status.write_text(status.read_text().replace("baseline\t0", "baseline\t1"))
        result = self.collect(1, True)
        self.assertIn("status_exit_disagreement", result["rows"][0]["result"]["reason_codes"])

    def test_phase_timeout_or_signal_cannot_hide_in_passed_worker(self):
        self.batch(pairs=1)
        for changes in ({"exit_code": -11}, {"timed_out": True}):
            payload = self.payload()
            payload["phases"][0].update(changes)
            (self.root / "1-pkg-baseline.log").write_text(json.dumps({"rootless_workload": payload}))
            result = self.collect(1, True)
            self.assertEqual(result["rows"][0]["result"]["status"], "failed")
        payload = self.payload(0)
        payload["phases"] = []
        (self.root / "1-pkg-baseline.log").write_text(json.dumps({"rootless_workload": payload}))
        result = self.collect(1, True)
        self.assertIn("passed_worker_has_no_measured_work", result["rows"][0]["result"]["reason_codes"])

    def test_wrong_alternation_and_diagnostics_disqualify_timing(self):
        self.batch()
        path = self.root / "status.tsv"
        rows = path.read_text().splitlines(True)
        rows[2], rows[3] = rows[3], rows[2]
        path.write_text("".join(rows))
        result = self.collect()
        self.assertFalse(result["alternating_order_valid"])
        self.assertFalse(result["summaries"]["dpkg-1000"]["comparison_eligible"])
        self.batch()
        log = self.root / "1-pkg-baseline.log"
        log.write_text("proot-perf: enter_stop=100\n" + log.read_text())
        result = self.collect()
        self.assertTrue(result["instrumentation"]["diagnostics_observed"])
        self.assertEqual(result["input_errors"], ["diagnostic_instrumentation_contradicts_disabled_declaration"])
        self.assertFalse(result["summaries"]["dpkg-1000"]["comparison_eligible"])

    def test_duplicate_status_and_missing_input_hash_are_visible_failures(self):
        self.batch(pairs=1)
        status = self.root / "status.tsv"
        status.write_text(status.read_text() + "1\tpkg\tbaseline\t0\n")
        hashes = self.root / "inputs.sha256"
        hashes.write_text("\n".join(hashes.read_text().splitlines()[:-1]) + "\n")
        result = self.collect(1, True)
        self.assertEqual(result["status"], "failed")
        self.assertIn("duplicate_status_rows", result["rows"][0]["result"]["reason_codes"])
        self.assertIn("required_input_hash_missing", result["input_errors"])


if __name__ == "__main__":
    unittest.main()
