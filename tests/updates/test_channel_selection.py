import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "tools/updates/select-channel.py"
SPEC = importlib.util.spec_from_file_location("channel_selection", SCRIPT)
selection = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(selection)


class ChannelSelectionTest(unittest.TestCase):
    def test_pushes_keep_their_existing_automatic_channel(self):
        for channel in ("main", "dev"):
            self.assertEqual((channel, True), selection.select_channel("push", f"refs/heads/{channel}"))

    def test_default_manual_runs_sign_without_publishing(self):
        for channel in ("main", "dev"):
            self.assertEqual((channel, False), selection.select_channel("workflow_dispatch", f"refs/heads/{channel}"))

    def test_explicit_manual_publication_can_select_either_channel_from_dev(self):
        for channel in ("main", "dev"):
            self.assertEqual((channel, True), selection.select_channel("workflow_dispatch", "refs/heads/dev", channel))

    def test_other_refs_and_pull_requests_only_verify(self):
        for event, ref in (("push", "refs/heads/topic"), ("push", "refs/tags/test"),
                           ("pull_request", "refs/pull/1/merge"), ("pull_request", "refs/heads/dev"),
                           ("workflow_dispatch", "refs/heads/topic")):
            self.assertEqual((None, False), selection.select_channel(event, ref))

    def test_explicit_publication_rejects_other_refs_events_and_unknown_channels(self):
        for event, ref, channel in (("workflow_dispatch", "refs/heads/main", "main"),
                                    ("workflow_dispatch", "refs/tags/dev", "dev"),
                                    ("workflow_dispatch", "refs/heads/topic", "main"),
                                    ("push", "refs/heads/dev", "main"),
                                    ("pull_request", "refs/heads/dev", "dev"),
                                    ("workflow_dispatch", "refs/heads/dev", "unknown"),
                                    ("workflow_dispatch", "refs/heads/dev", "main\npublish=true")):
            with self.subTest(event=event, ref=ref, channel=channel), self.assertRaises(ValueError):
                selection.select_channel(event, ref, channel)


class ChannelConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release-test@example.invalid")
        self.git("commit", "--quiet", "--allow-empty", "-m", "Stable fixture")
        self.main = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/main", self.main)
        self.git("commit", "--quiet", "--allow-empty", "-m", "Development fixture")
        self.commit = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/dev", self.commit)

    def git(self, *arguments):
        return subprocess.check_output(["git", *arguments], cwd=self.root, text=True, stderr=subprocess.PIPE).strip()

    def run_selector(self, **overrides):
        environment = dict(os.environ, GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_REF="refs/heads/dev",
                           GITHUB_SHA=self.commit, GITHUB_RUN_NUMBER="123", TERMINAL_VERSION_CODE_OFFSET="70",
                           TERMINAL_PUBLISH_CHANNEL="main", GITHUB_OUTPUT=str(self.root / "output"),
                           GITHUB_ENV=str(self.root / "environment"))
        environment.update(overrides)
        return subprocess.run([sys.executable, str(SCRIPT)], cwd=self.root, env=environment, text=True, capture_output=True)

    def test_configuration_uses_event_sha_shared_sequence_and_preserves_main(self):
        result = self.run_selector()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("channel=main\npublish=true\n", (self.root / "output").read_text())
        self.assertEqual(f"TERMINAL_UPDATE_CHANNEL=main\nTERMINAL_UPDATE_REVISION={self.commit}\n"
                         "TERMINAL_UPDATE_VERSION_CODE=1000193\n", (self.root / "environment").read_text())
        self.assertEqual(self.main, self.git("rev-parse", "refs/remotes/origin/main"))
        self.assertEqual(self.commit, self.git("rev-parse", "refs/remotes/origin/dev"))

    def test_non_shared_commit_fails_before_exposing_any_output(self):
        outside = self.git("commit-tree", self.git("rev-parse", "HEAD^{tree}"), "-m", "Unrelated fixture")
        result = self.run_selector(GITHUB_SHA=outside)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("shared dev history", result.stderr)
        self.assertFalse((self.root / "output").exists())
        self.assertFalse((self.root / "environment").exists())

    def test_non_publication_ref_cannot_expose_release_configuration(self):
        result = self.run_selector(GITHUB_EVENT_NAME="pull_request", GITHUB_REF="refs/pull/1/merge",
                                   TERMINAL_PUBLISH_CHANNEL="none")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("channel=\npublish=false\n", (self.root / "output").read_text())
        self.assertFalse((self.root / "environment").exists())


if __name__ == "__main__":
    unittest.main()
