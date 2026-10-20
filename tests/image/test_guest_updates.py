import importlib.util
import contextlib
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, REPO / "image/scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


migration = load("migrate-avf-state")
packaging = load("build-guest-package")


class GuestUpdatesTest(unittest.TestCase):
    def test_factory_payload_supplies_every_guest_configuration_input(self):
        # Check the producer/consumer boundary without requiring an appliance boot.
        dockerfile = (REPO / "image/scripts/Dockerfile").read_text()
        payload = set()
        for line in dockerfile.splitlines():
            if line.startswith("COPY "):
                fields = shlex.split(line)
                destination = Path(fields[-1])
                if str(destination).startswith("/payload/"):
                    payload.add(destination.name)
                    if not fields[1].startswith("--from="):
                        for source in fields[1:-1]:
                            self.assertTrue((REPO / source).is_file(), source)
        configure = (REPO / "image/scripts/configure-avf-guest.sh").read_text()
        required = set(re.findall(r"/tmp/term-avf/([\w.-]+)", configure))
        required.update(shlex.split(re.search(r"for script in ([^;]+); do", configure).group(1)))
        self.assertFalse(required - payload, f"Missing guest payload: {sorted(required - payload)}")

    def test_component_update_installs_exact_approved_local_version_even_if_hash_sorts_earlier(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            distribution = root / "etc/droid/distribution"
            distribution.parent.mkdir(parents=True)
            distribution.write_text("debian")
            with patch.object(migration, "OLD", root), \
                    patch.object(migration, "Path", side_effect=lambda name: distribution if name == "/etc/droid/distribution" else Path(name)), \
                    patch.object(migration, "offline_packages", return_value=contextlib.nullcontext()), \
                    patch.object(migration.shutil, "copy2"), patch.object(migration, "run") as run:
                migration.main("components")
            installs = [call.args for call in run.call_args_list if "install" in call.args]
            self.assertEqual(len(installs), 1)
            self.assertIn("--allow-downgrades", installs[0])
            self.assertEqual(installs[0][-1], "/tmp/term-guest.deb")

    def test_candidate_dns_is_captured_before_replacement_and_policy_restored_on_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            policy, resolver = root / "usr/sbin/policy-rc.d", root / "etc/resolv.conf"
            policy.parent.mkdir(parents=True)
            resolver.parent.mkdir(parents=True)
            policy.write_text("original policy")
            resolver.write_text("candidate DNS")
            # Emulate root=/ without modifying the host's resolver or package policy.
            with patch.object(migration, "Path", side_effect=lambda name: resolver if name == "/etc/resolv.conf" else Path(name)):
                with self.assertRaisesRegex(RuntimeError, "package failure"):
                    with migration.offline_packages(root):
                        self.assertEqual(resolver.read_text(), "candidate DNS")
                        self.assertIn("exit 101", policy.read_text())
                        raise RuntimeError("package failure")
            self.assertEqual(policy.read_text(), "original policy")
            self.assertEqual(resolver.read_text(), "candidate DNS")

    def test_three_way_merge_preserves_user_edits_and_deletions_without_overwriting_new_defaults(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old, lower, target = [root / p for p in ("old", "lower", "new")]
            for directory in (old, lower, target):
                directory.mkdir()
                (directory / "custom.conf").write_text("default")
                (directory / "deleted.conf").write_text("default")
                (directory / "term.conf").write_text("default")
            (old / "custom.conf").write_text("user setting")
            (old / "deleted.conf").unlink()
            (old / "term.conf").write_text("old service")
            (target / "term.conf").write_text("new service")
            migration.merge_config(old, lower, target, {"/etc/term.conf"})
            self.assertEqual((target / "custom.conf").read_text(), "user setting")
            self.assertFalse((target / "deleted.conf").exists())
            self.assertEqual((target / "term.conf").read_text(), "new service")

    def test_conflicting_config_and_symlink_parent_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old, lower, target = [root / p for p in ("old", "lower", "new")]
            for directory, text in ((old, "user"), (lower, "before"), (target, "after")):
                directory.mkdir()
                (directory / "custom.conf").write_text(text)
            with self.assertRaisesRegex(RuntimeError, "Configuration conflict"):
                migration.merge_config(old, lower, target, set())
            outside = root / "outside"
            outside.mkdir()
            (target / "link").symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(RuntimeError, "symlink parent"):
                migration.copy_entry(old / "custom.conf", target / "link/overwrite")
            self.assertFalse((outside / "overwrite").exists())

    def test_package_selection_excludes_base_and_rejects_options(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline = root / "usr/share/term/base-packages"
            baseline.parent.mkdir(parents=True)
            baseline.write_text("base\n")
            with patch.object(migration.subprocess, "check_output", return_value="base\ngit\nterm-guest\n"):
                self.assertEqual(migration.user_packages(root, root, "debian"), ["git"])
            with patch.object(migration.subprocess, "check_output", return_value="--allow-unauthenticated\n"):
                with self.assertRaisesRegex(RuntimeError, "Invalid package"):
                    migration.user_packages(root, root, "debian")

    def test_debian_ubuntu_and_arch_packages_contain_only_managed_files(self):
        for distribution in ("debian", "ubuntu", "archlinux"):
            with self.subTest(distribution=distribution), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                binary = root / "usr/local/lib/droid/droid-sessiond"
                binary.parent.mkdir(parents=True)
                binary.write_bytes(b"guest binary")
                binary.chmod(0o755)
                (root / "private-key").write_text("must not be packaged")
                with patch.object(packaging.subprocess, "check_output", return_value=b"base\n"):
                    packaging.build(root, distribution)
                output = root / "usr/share/term"
                if distribution == "archlinux":
                    with tarfile.open(output / "term-guest.pkg.tar") as package:
                        self.assertIn("usr/local/lib/droid/droid-sessiond", package.getnames())
                        self.assertNotIn("private-key", package.getnames())
                        self.assertIn(b"arch = aarch64", package.extractfile(".PKGINFO").read())
                else:
                    package = output / "term-guest.deb"
                    self.assertEqual(subprocess.check_output(["dpkg-deb", "--field", str(package), "Architecture"], text=True).strip(), "arm64")
                    listing = subprocess.check_output(["dpkg-deb", "--contents", str(package)], text=True)
                    self.assertIn("droid-sessiond", listing)
                    self.assertNotIn("private-key", listing)


if __name__ == "__main__":
    unittest.main()
