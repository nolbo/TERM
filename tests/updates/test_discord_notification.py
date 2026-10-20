import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import textwrap
import unittest
from unittest.mock import patch


WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/discord-release.yml"
SCRIPT = textwrap.dedent(WORKFLOW.read_text().split("          python3 - <<'PY'\n", 1)[1].rsplit("          PY", 1)[0])


class Response(io.BytesIO):
    status = 200


class DiscordNotificationTest(unittest.TestCase):
    def notify(self, event, tag="", release=None):
        calls = []

        def urlopen(request, **kwargs):
            calls.append(request)
            if request.get_method() == "GET":
                return Response(json.dumps(release).encode())
            return Response(b"{}")

        with tempfile.TemporaryDirectory() as directory:
            event_file = Path(directory) / "event.json"
            event_file.write_text(json.dumps(event))
            environment = dict(TERM_RELEASE_TAG=tag, GH_TOKEN="github-fixture",
                               DISCORD_BOT_TOKEN="discord-fixture", DISCORD_CHANNEL_ID="1234",
                               GITHUB_REPOSITORY="nolbo/TERM", GITHUB_EVENT_PATH=str(event_file))
            with patch.dict(os.environ, environment), patch("urllib.request.urlopen", side_effect=urlopen), \
                    contextlib.redirect_stdout(io.StringIO()):
                try:
                    exec(compile(SCRIPT, str(WORKFLOW), "exec"), {})
                except RuntimeError as error:
                    return calls, error
        return calls, None

    def release(self, channel="main", **overrides):
        tag = channel + "-" + "1" * 40
        return dict(tag_name=tag, name=f"1.1.0+{channel}.1000087.111111111111",
                    body="한국어 출시 안내", html_url=f"https://github.com/nolbo/TERM/releases/tag/{tag}",
                    prerelease=channel == "dev", draft=False, **overrides)

    def test_manual_recovery_fetches_exact_release_and_sends_channel_specific_notes(self):
        for channel in ("main", "dev"):
            with self.subTest(channel=channel):
                release = self.release(channel)
                calls, error = self.notify({}, tag=release["tag_name"], release=release)
                self.assertIsNone(error)
                self.assertEqual(len(calls), 2)
                self.assertEqual(calls[0].full_url, "https://api.github.com/repos/nolbo/TERM/releases/tags/" + release["tag_name"])
                self.assertEqual(calls[1].full_url, "https://discord.com/api/v10/channels/1234/messages")
                payload = json.loads(calls[1].data)
                embed = payload["embeds"][0]
                self.assertEqual(embed["title"], ("[Pre-release] " if channel == "dev" else "") + release["name"])
                self.assertEqual(embed["description"], release["body"])
                self.assertEqual(embed["url"], release["html_url"])
                self.assertEqual(embed["fields"][1]["value"], release["tag_name"])
                self.assertEqual(payload["allowed_mentions"], {"parse": []})

    def test_published_release_event_still_sends_its_notes(self):
        release = self.release()
        calls, error = self.notify({"release": release})
        self.assertIsNone(error)
        self.assertEqual(len(calls), 1)
        self.assertEqual(json.loads(calls[0].data)["embeds"][0]["description"], release["body"])

    def test_draft_or_mismatched_release_is_rejected_before_discord(self):
        for change in ({"draft": True}, {"tag_name": "another-tag"}):
            with self.subTest(change=change):
                release = self.release()
                tag = release["tag_name"]
                release.update(change)
                calls, error = self.notify({}, tag=tag, release=release)
                self.assertIsInstance(error, RuntimeError)
                self.assertEqual(len(calls), 1)
                self.assertEqual(calls[0].get_method(), "GET")

    def test_missing_release_never_sends_a_test_message(self):
        calls, error = self.notify({})
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
