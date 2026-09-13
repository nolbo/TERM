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
    print(json.dumps({"name": os.environ["LATEST_NAME"]}))
elif args[:2] == ["release", "view"]:
    state = os.environ["RELEASE_STATE"]
    if state == "missing":
        sys.exit(1)
    print("true" if state == "draft" else "false")
'''


class PublishTest(unittest.TestCase):
    def run_publisher(self, latest="0.1.0+main.31.111111111111", state="missing", error=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gh = root / "gh"
            gh.write_text(FAKE_GH)
            gh.chmod(0o700)
            artifact = root / "update"
            artifact.mkdir()
            (artifact / "TERM.apk").write_bytes(b"fixture")
            (artifact / "update.json").write_text(json.dumps(dict(
                commit="2" * 40, versionCode=32, versionName="0.1.0+main.32.222222222222")))
            calls = root / "calls"
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", GH_TOKEN="test-fixture",
                       GH_CALLS=str(calls), LATEST_NAME=latest, RELEASE_STATE=state)
            if error:
                env["LATEST_ERROR"] = error
            script = Path(__file__).resolve().parents[2] / "tools/updates/publish.sh"
            result = subprocess.run(["bash", str(script), str(artifact)], env=env, capture_output=True, text=True)
            return result, [json.loads(line) for line in calls.read_text().splitlines()]

    def test_first_release_and_forward_release_become_latest_only_after_upload(self):
        for error in [None, "gh: Not Found (HTTP 404)"]:
            with self.subTest(error=error):
                result, calls = self.run_publisher(error=error)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls[-2][:2], ["release", "create"])
                self.assertIn("--draft", calls[-2])
                self.assertIn("--latest=true", calls[-1])

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
