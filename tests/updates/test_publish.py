import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


FAKE_GH = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with Path(os.environ["GH_CALLS"]).open("a") as log:
    log.write(json.dumps(args) + "\\n")
if args[0] == "api":
    error = os.environ.get("LATEST_ERROR")
    if error:
        print(error, file=sys.stderr)
        sys.exit(1)
    print(json.dumps({"name": os.environ["LATEST_NAME"], "draft": os.environ["POINTER_DRAFT"] == "true"}))
elif args[:3] == ["release", "upload", "dev-latest"] and os.environ.get("POINTER_UPLOAD_ERROR"):
    print("metadata upload failed", file=sys.stderr)
    sys.exit(1)
elif args[:2] == ["release", "view"]:
    state = os.environ["RELEASE_STATE"]
    if state == "missing":
        sys.exit(1)
    print("true" if state == "draft" else "false")
'''


class PublishTest(unittest.TestCase):
    def run_publisher(self, latest="0.1.0+main.31.111111111111", state="missing", error=None,
                      channel="main", pointer_draft=False, pointer_upload_error=False, notes=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gh = root / "gh"
            gh.write_text(FAKE_GH)
            gh.chmod(0o700)
            artifact = root / "update"
            artifact.mkdir()
            (artifact / "TERM.apk").write_bytes(b"fixture")
            (artifact / "update.json").write_text(json.dumps(dict(
                commit="2" * 40, versionCode=32, channel=channel, versionName=f"0.1.0+{channel}.32.222222222222")))
            if notes:
                (artifact / "RELEASE_NOTES.ko.md").write_text("한국어 릴리즈 노트")
            calls = root / "calls"
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", GH_TOKEN="test-fixture",
                       GH_CALLS=str(calls), LATEST_NAME=latest, RELEASE_STATE=state,
                       POINTER_DRAFT=str(pointer_draft).lower())
            if error:
                env["LATEST_ERROR"] = error
            if pointer_upload_error:
                env["POINTER_UPLOAD_ERROR"] = "true"
            script = Path(__file__).resolve().parents[2] / "tools/updates/publish.sh"
            result = subprocess.run(["bash", str(script), str(artifact)], env=env, capture_output=True, text=True)
            return result, [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []

    def test_korean_notes_reach_release_and_dev_pointer(self):
        result, calls = self.run_publisher(channel="dev", latest="0.1.0+dev.31.111111111111", notes=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        writes = [call for call in calls if call[:2] in (["release", "create"], ["release", "edit"])]
        self.assertEqual(len(writes), 3)
        for call in writes:
            self.assertIn("--notes-file", call)
            self.assertTrue(call[call.index("--notes-file") + 1].endswith("RELEASE_NOTES.ko.md"))

    def test_first_release_and_forward_release_become_latest_only_after_upload(self):
        for error in [None, "gh: Not Found (HTTP 404)"]:
            with self.subTest(error=error):
                result, calls = self.run_publisher(error=error)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls[-2][:2], ["release", "create"])
                self.assertIn("--draft", calls[-2])
                self.assertIn("--latest=true", calls[-1])
                self.assertEqual(calls[-2][calls[-2].index("--target") + 1], "2" * 40)

    def test_late_older_release_does_not_replace_latest(self):
        result, calls = self.run_publisher(latest="0.1.0+main.33.333333333333")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--latest=false", calls[-1])

    def test_published_assets_are_never_overwritten(self):
        result, calls = self.run_publisher(state="published")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 2)

    def test_only_draft_assets_can_be_replaced(self):
        result, calls = self.run_publisher(state="draft")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[-2][:2], ["release", "upload"])
        self.assertIn("--clobber", calls[-2])

    def test_api_errors_and_unrecognized_latest_stop_without_writes(self):
        for kwargs in [dict(error="gh: Forbidden (HTTP 403)"), dict(latest="unrecognized")]:
            with self.subTest(kwargs=kwargs):
                result, calls = self.run_publisher(**kwargs)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(len(calls), 1)

    def test_first_dev_release_is_prerelease_and_only_metadata_goes_in_its_pointer(self):
        result, calls = self.run_publisher(channel="dev", error="gh: Not Found (HTTP 404)")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[0], ["api", "repos/nolbo/TERM/releases/tags/dev-latest"])
        self.assertIn("--prerelease=true", calls[2])
        pointer = calls[-2]
        self.assertEqual(pointer[:3], ["release", "create", "dev-latest"])
        self.assertTrue(pointer[-1].endswith("/update.json"))
        self.assertFalse(any("--latest=true" in call for call in calls))

    def test_new_dev_metadata_advances_without_replacing_published_apks(self):
        result, calls = self.run_publisher(channel="dev", latest="0.1.0+dev.31.111111111111", state="published")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[-2][:3], ["release", "upload", "dev-latest"])
        uploads = [call for call in calls if call[:2] == ["release", "upload"]]
        self.assertEqual(len(uploads), 1)
        self.assertTrue(uploads[0][5].endswith("/update.json"))

    def test_late_dev_build_cannot_move_the_pointer_backwards(self):
        result, calls = self.run_publisher(channel="dev", latest="0.1.0+dev.33.333333333333", state="published")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 2)

    def test_dev_pointer_upload_failure_can_retry_without_mutating_the_apk(self):
        kwargs = dict(channel="dev", latest="0.1.0+dev.31.111111111111", state="published")
        failed, calls = self.run_publisher(**kwargs, pointer_upload_error=True)
        self.assertNotEqual(failed.returncode, 0)
        self.assertEqual(calls[-1][:3], ["release", "upload", "dev-latest"])
        retried, calls = self.run_publisher(**kwargs)
        self.assertEqual(retried.returncode, 0, retried.stderr)
        self.assertEqual(calls[-1][:3], ["release", "edit", "dev-latest"])

    def test_draft_pointer_can_complete_on_retry_of_the_same_version(self):
        result, calls = self.run_publisher(channel="dev", latest="0.1.0+dev.32.222222222222",
                                           state="published", pointer_draft=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[-1][:3], ["release", "edit", "dev-latest"])

    def test_unknown_channel_fails_before_any_github_call(self):
        result, calls = self.run_publisher(channel="other")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])
