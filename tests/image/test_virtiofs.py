import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class VirtiofsGuestTests(unittest.TestCase):
    def run_mounts(self, root, table, fail_tag="", cmdline="root=/dev/vda term.virtiofs=1"):
        binaries = root / "bin"
        binaries.mkdir()
        helper = """#!/usr/bin/python3
import json, os, pathlib, sys
tool = pathlib.Path(sys.argv[0]).name
with open(os.environ['MOUNT_LOG'], 'a') as output:
    output.write(json.dumps([tool] + sys.argv[1:]) + '\\n')
if tool == 'mount' and sys.argv[-2] == os.environ.get('FAIL_TAG'):
    sys.exit(1)
"""
        for name in ["mount", "umount", "modprobe"]:
            executable = binaries / name
            executable.write_text(helper)
            executable.chmod(0o755)
        index = root / "index"
        index.mkdir()
        (index / "mounts").write_text(table)
        (root / "cmdline").write_text(cmdline)
        log = root / "calls"
        result = subprocess.run(["sh", str(REPO / "image/scripts/mount-shared-directories.sh"),
                                 str(root / "cmdline"), str(index), str(root / "shares")],
                                env={**os.environ, "PATH": str(binaries) + ":" + os.environ["PATH"],
                                     "TMPDIR": str(root), "MOUNT_LOG": str(log), "FAIL_TAG": fail_tag},
                                capture_output=True, text=True, umask=0o077)
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return result, calls

    def test_long_names_mount_as_live_filesystems_with_safe_options(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            name = "A" * 64
            tag = "ts-" + "a" * 32
            result, calls = self.run_mounts(root, f"1\n{tag}\t{name}\n")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(["mount", "-t", "virtiofs", "-o", "ro,nodev,nosuid,noexec",
                           "term-shares", str(root / "index")], calls)
            self.assertIn(["mount", "-t", "virtiofs", "-o", "nodev,nosuid", tag, str(root / "shares" / name)], calls)
            self.assertFalse(any(call[0] == "umount" for call in calls))

    def test_mount_parent_remains_searchable_by_guest_under_private_service_umask(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result, _ = self.run_mounts(root, f"1\nts-{'a' * 32}\tProject\n")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(0o755, (root / "shares").stat().st_mode & 0o777)

    def test_invalid_tables_are_rejected_before_any_user_directory_mount(self):
        tag = "ts-" + "a" * 32
        for table in [f"2\n{tag}\tCode\n", f"1\n{tag}\t../private\n",
                      f"1\n{tag}\tCode\n{tag}\tOther\n",
                      f"1\n{tag}\tCode\nts-{'b' * 32}\tCode\n",
                      f"1\n{tag}\tCode\textra\n", "1\n", "x" * 16385]:
            with self.subTest(table=table[:100]), tempfile.TemporaryDirectory() as temporary:
                result, calls = self.run_mounts(Path(temporary), table)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(call[0] == "mount" and call[-2].startswith("ts-") for call in calls))
                self.assertTrue(any(call[0] == "umount" for call in calls))

    def test_failed_mount_rolls_back_only_this_boots_mounts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first, second = "ts-" + "a" * 32, "ts-" + "b" * 32
            result, calls = self.run_mounts(root, f"1\n{first}\tOne\n{second}\tTwo\n", fail_tag=second)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([["umount", str(root / "shares/One")], ["umount", str(root / "index")]],
                             [call for call in calls if call[0] == "umount"])

    def test_boot_without_shares_uses_no_mounts_or_modules(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, calls = self.run_mounts(Path(temporary), "bad table", cmdline="root=/dev/vda")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], calls)

    def test_factory_installs_mounts_before_session_daemon_without_overwriting_other_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dropins = root / "etc/systemd/system/droid-sessiond.service.d"
            dropins.mkdir(parents=True)
            (dropins / "access.conf").write_text("[Service]\nGroup=droid\n")
            subprocess.run(["sh", str(REPO / "image/scripts/configure-avf-shares.sh"), str(root),
                            str(REPO / "image/scripts")], check=True)
            self.assertEqual("[Service]\nGroup=droid\n", (dropins / "access.conf").read_text())
            self.assertIn("Requires=term-shares.service", (dropins / "shares.conf").read_text())
            script = root / "usr/local/lib/droid/mount-shared-directories.sh"
            self.assertEqual(0o755, script.stat().st_mode & 0o777)
            self.assertEqual((REPO / "image/scripts/mount-shared-directories.sh").read_bytes(), script.read_bytes())
            subprocess.run(["systemctl", f"--root={root}", "is-enabled", "--quiet", "term-shares.service"], check=True)


if __name__ == "__main__":
    unittest.main()
