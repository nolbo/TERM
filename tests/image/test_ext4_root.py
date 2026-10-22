import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ext4_migration", REPO / "image/scripts/migrate-avf-state.py")
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


class Ext4RootTest(unittest.TestCase):
    @unittest.skipUnless(all(shutil.which(tool) for tool in ("mkfs.ext4", "debugfs", "tune2fs", "resize2fs", "e2fsck")),
                         "e2fsprogs required")
    def test_factory_root_has_all_seed_files_independent_identity_and_resets_probe_writes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            content = root / "content"
            (content / "etc").mkdir(parents=True)
            (content / "etc/fixture").write_text("sealed base")
            os.link(content / "etc/fixture", content / "etc/hardlink")
            (content / "etc/link").symlink_to("fixture")
            seed, disk = root / "system.img", root / "home.img"
            with seed.open("wb") as output:
                output.truncate(32 * 1024 * 1024)
            subprocess.run(["mkfs.ext4", "-q", "-F", "-d", str(content), str(seed)], check=True, capture_output=True)
            before = hashlib.sha256(seed.read_bytes()).digest()
            command = ["bash", str(REPO / "image/scripts/initialize-avf-root.sh"), str(seed), str(disk), str(48 * 1024 * 1024)]
            def debug(image, request, write=False):
                return subprocess.run(["debugfs", *( ["-w"] if write else []), "-R", request, str(image)],
                                      check=True, capture_output=True, text=True).stdout
            for _ in range(2):
                subprocess.run(command, check=True, capture_output=True)
                self.assertEqual(debug(disk, "cat /etc/fixture"), "sealed base")
                self.assertEqual(debug(disk, "cat /etc/hardlink"), "sealed base")
                self.assertIn('Fast link dest: "fixture"', debug(disk, "stat /etc/link"))
                self.assertIn("Links: 2", debug(disk, "stat /etc/fixture"))
                self.assertNotEqual(seed.stat().st_ino, disk.stat().st_ino)
                self.assertEqual(disk.stat().st_size, 48 * 1024 * 1024)
                def uuid(image):
                    return next(line for line in subprocess.check_output(["tune2fs", "-l", str(image)], text=True).splitlines()
                                if line.startswith("Filesystem UUID:"))
                self.assertNotEqual(uuid(seed), uuid(disk))
                debug(disk, "rm /etc/fixture", write=True)
                self.assertEqual(before, hashlib.sha256(seed.read_bytes()).digest())
            subprocess.run(["e2fsck", "-fn", str(disk)], check=True, capture_output=True)
            rejected = subprocess.run([*command[:2], str(seed), str(seed)], capture_output=True)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertEqual(before, hashlib.sha256(seed.read_bytes()).digest())

    def test_v1_and_v2_data_migration_preserves_linux_data_and_new_package_state(self):
        for version in (1, 2):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                old, lower, target, state = [root / name for name in ("old", "lower", "target", "state")]
                def write(base, name, contents):
                    path = base / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(contents)
                    return path
                for base in (old, lower, target):
                    write(base, "usr/share/term/managed-files", "/usr/local/lib/droid/managed\n")
                    write(base, "usr/local/lib/droid/managed", "new" if base == target else "old")
                    write(base, "var/lib/dpkg/status", "new" if base == target else "old")
                data = write(old, "home/droid/file", "user data")
                data.chmod(0o640)
                os.setxattr(data, "user.term-test", b"metadata")
                sparse = old / "home/droid/sparse"
                with sparse.open("wb") as output:
                    output.seek(8 * 1024 * 1024)
                    output.write(b"tail")
                hardlink = old / "var/lib/application/data"
                hardlink.parent.mkdir(parents=True)
                os.link(data, hardlink)
                (old / "home/droid/link").symlink_to("missing-target")
                os.mkfifo(old / "home/droid/fifo", 0o600)
                write(old, "usr/local/bin/custom", "custom binary")
                direct = state if version == 1 else old / ".droid/state"
                write(direct, "docker/volumes/data", "container data")
                if version == 1:
                    write(state, "upper/sentinel", "never migrate raw upper")
                    write(state, "work/sentinel", "never migrate raw work")
                migration.migrate_data(old, lower, target, state, version)
                copied = target / "home/droid/file"
                self.assertEqual(copied.read_text(), "user data")
                self.assertEqual(stat.S_IMODE(copied.stat().st_mode), 0o640)
                self.assertEqual(os.getxattr(copied, "user.term-test"), b"metadata")
                copied_sparse = target / "home/droid/sparse"
                self.assertEqual(copied_sparse.read_bytes(), sparse.read_bytes())
                self.assertLess(copied_sparse.stat().st_blocks * 512, copied_sparse.stat().st_size)
                self.assertEqual(copied.stat().st_uid, data.stat().st_uid)
                self.assertEqual(copied.stat().st_ino, (target / "var/lib/application/data").stat().st_ino)
                self.assertEqual(os.readlink(target / "home/droid/link"), "missing-target")
                self.assertTrue(stat.S_ISFIFO((target / "home/droid/fifo").stat().st_mode))
                self.assertEqual((target / "usr/local/bin/custom").read_text(), "custom binary")
                self.assertEqual((target / "usr/local/lib/droid/managed").read_text(), "new")
                self.assertEqual((target / "var/lib/dpkg/status").read_text(), "new")
                self.assertEqual((target / ".droid/state/docker/volumes/data").read_text(), "container data")
                self.assertFalse((target / ".droid/state/upper").exists())
                self.assertEqual(data.read_text(), "user data")

    def test_links_cannot_replace_managed_parents_or_escape_destination(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            external = root / "external"
            external.mkdir()
            source, target = root / "source", root / "target"
            source.symlink_to(external)
            target.mkdir()
            (target / "managed").write_text("approved")
            with self.assertRaisesRegex(RuntimeError, "managed parent"):
                migration.copy_data(source, target, {Path("usr/local/managed")}, {}, Path("usr/local"))
            self.assertEqual((target / "managed").read_text(), "approved")
            source.unlink()
            source.write_text("data")
            (target / "link").symlink_to(external)
            with self.assertRaisesRegex(RuntimeError, "symlink parent"):
                migration.copy_data(source, target / "link/escape", set(), {})
            self.assertEqual(list(external.iterdir()), [])
            old = root / "old"
            (old / ".droid").mkdir(parents=True)
            (old / ".droid/state").symlink_to(external)
            with self.assertRaisesRegex(RuntimeError, "state directory must not be a symlink"):
                migration.migrate_data(old, root / "lower", target, root / "state", 2)

    def test_maintenance_uses_the_host_selected_source_layout_and_rejects_unknown_formats(self):
        for version in (1, 2, 9):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                work, tools = root / "maintenance", root / "bin"
                tools.mkdir()
                (work / "state/upper").mkdir(parents=True)
                commandline, log = root / "cmdline", root / "calls"
                commandline.write_text(f"root=/dev/vdb term.maintenance=rebase term.source-format={version}")
                for tool in ("mount", "umount", "sync", "python3"):
                    fake = tools / tool
                    fake.write_text(f'#!/bin/sh\nprintf "%s\\n" "{tool} $*" >> "$TERM_TEST_CALLS"\n')
                    fake.chmod(0o755)
                script = (REPO / "image/scripts/maintain-avf-image.sh").read_text()
                script = script.replace("/run/term-maintenance", str(work)).replace("/proc/cmdline", str(commandline))
                script = script.replace("/var/lib/droid", str(root / "receipts"))
                # Keep the tool fakes first; all writes stay in this fixture.
                script = script.replace("export PATH=/usr/sbin:/usr/bin:/sbin:/bin", f"export PATH={tools}:/usr/bin:/bin")
                result = subprocess.run(["sh", "-c", script], capture_output=True, text=True,
                                        env={**os.environ, "TERM_TEST_CALLS": str(log)})
                calls = log.read_text()
                self.assertEqual(result.returncode == 0, version in (1, 2), result.stderr)
                self.assertIn("mount -t ext4 -o ro,noload /dev/vdd", calls)
                if version == 1:
                    self.assertIn("mount -t overlay", calls)
                elif version == 2:
                    self.assertIn(f"mount --bind {work}/state {work}/old", calls)
                    self.assertNotIn("mount -t overlay", calls)
                self.assertEqual("python3 /usr/local/lib/droid/migrate-avf-state.py rebase" in calls, version in (1, 2))


if __name__ == "__main__":
    unittest.main()
