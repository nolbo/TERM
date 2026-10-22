import importlib.util
import contextlib
import os
from pathlib import Path
import re
import shlex
import stat
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


class AccountMigrationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.old, self.lower, self.target = [self.root / name for name in ("old", "lower", "new")]
        baseline = {
            "passwd": "root:x:0:0:root:/root:/bin/bash\ndroid:x:1000:1000::/home/droid:/bin/bash\n",
            "shadow": "root:!:20000:0:99999:7:::\ndroid:!:20000:0:99999:7:::\n",
            "group": "root:x:0:\ndroid:x:1000:\nsudo:x:27:droid\n",
            "gshadow": "root:!::\ndroid:!::\nsudo:!::droid\n",
        }
        for directory in (self.old, self.lower, self.target):
            directory.mkdir()
            for name, contents in baseline.items():
                path = directory / name
                path.write_text(contents)
                path.chmod(0o640 if name in ("shadow", "gshadow") else 0o644)

    def group(self, directory, name, gid, members="", password="x"):
        for database, record in (("group", f"{name}:{password}:{gid}:{members}\n"),
                                 ("gshadow", f"{name}:!::{members}\n")):
            path = directory / database
            lines = [line for line in path.read_text().splitlines(True) if not line.startswith(name + ":")]
            path.write_text("".join(lines) + record)

    def user(self, directory, name, uid, gid):
        with (directory / "passwd").open("a") as output:
            output.write(f"{name}:x:{uid}:{gid}::/home/{name}:/bin/bash\n")
        with (directory / "shadow").open("a") as output:
            output.write(f"{name}:!:20000:0:99999:7:::\n")

    def merge(self):
        migration.merge_config(self.old, self.lower, self.target, set())

    def snapshot(self, directory):
        return {p.name: p.read_bytes() for p in directory.iterdir() if p.is_file() and not p.is_symlink()}

    def assert_conflict_unchanged(self, reason):
        before = self.snapshot(self.target)
        original = self.snapshot(self.old)
        with self.assertRaisesRegex(RuntimeError, reason):
            self.merge()
        self.assertEqual(self.snapshot(self.target), before)
        self.assertEqual(self.snapshot(self.old), original)

    def test_independent_package_groups_and_user_memberships_merge(self):
        self.group(self.old, "docker", 1001, "droid")
        self.group(self.target, "render", 109)
        self.group(self.old, "sudo", 27, "")
        self.group(self.target, "sudo", 27, "droid,root")
        original, base = self.snapshot(self.old), self.snapshot(self.lower)
        self.merge()
        group = (self.target / "group").read_text()
        self.assertIn("docker:x:1001:droid\n", group)
        self.assertIn("render:x:109:\n", group)
        self.assertIn("sudo:x:27:root\n", group)
        self.assertIn("sudo:!::root\n", (self.target / "gshadow").read_text())
        self.assertEqual(self.snapshot(self.old), original)
        self.assertEqual(self.snapshot(self.lower), base)
        merged = self.snapshot(self.target)
        self.merge()
        self.assertEqual(self.snapshot(self.target), merged)

    def test_same_new_group_merges_members_without_losing_either_side(self):
        self.group(self.old, "docker", 1001, "droid")
        self.group(self.target, "docker", 1001, "root")
        self.merge()
        self.assertIn("docker:x:1001:droid,root\n", (self.target / "group").read_text())
        self.assertIn("docker:!::droid,root\n", (self.target / "gshadow").read_text())

    def test_reported_ubuntu_ssh_and_docker_groups_survive_changed_entry_order(self):
        # Reported old overlay appends _ssh:102 and docker:103. The new factory
        # already contains _ssh:102 at a different position in the file.
        self.group(self.old, "_ssh", 102)
        self.group(self.old, "docker", 103)
        for database, line in (("group", "_ssh:x:102:\n"), ("gshadow", "_ssh:!::\n")):
            path = self.target / database
            path.write_text(line + path.read_text())
        self.merge()
        records = migration.read_accounts(self.target, "group")
        self.assertEqual(records["_ssh"], ("_ssh", "x", "102", ""))
        self.assertEqual(records["docker"], ("docker", "x", "103", ""))
        self.assertEqual((self.target / "group").read_text().count("_ssh:"), 1)
        self.assertIn("docker:!::\n", (self.target / "gshadow").read_text())

    def test_user_account_password_and_new_service_account_are_retained(self):
        self.group(self.old, "alice", 1001)
        self.user(self.old, "alice", 1001, 1001)
        self.group(self.target, "service", 109)
        self.user(self.target, "service", 109, 109)
        (self.old / "shadow").write_text((self.old / "shadow").read_text().replace("droid:!:", "droid:locked-test-value:"))
        (self.target / "passwd").write_text((self.target / "passwd").read_text().replace("1000:1000::", "1000:1000:TERM user:"))
        self.merge()
        self.assertIn("alice:x:1001:1001:", (self.target / "passwd").read_text())
        self.assertIn("service:x:109:109:", (self.target / "passwd").read_text())
        self.assertIn("droid:x:1000:1000:TERM user:", (self.target / "passwd").read_text())
        self.assertIn("droid:locked-test-value:", (self.target / "shadow").read_text())
        for name in ("shadow", "gshadow"):
            self.assertEqual(stat.S_IMODE((self.target / name).stat().st_mode), 0o640)
            self.assertEqual((self.target / name).stat().st_uid, os.getuid())

    def test_generated_account_backups_and_lock_are_not_replayed(self):
        for name in ("group-", "gshadow-", "passwd-", "shadow-", ".pwd.lock"):
            for directory, value in ((self.lower, "base"), (self.old, "old backup"), (self.target, "candidate backup")):
                (directory / name).write_text(value)
        self.group(self.old, "docker", 1001, "droid")
        self.group(self.target, "render", 109)
        self.merge()
        for name in ("group-", "gshadow-", "passwd-", "shadow-", ".pwd.lock"):
            self.assertEqual((self.target / name).read_text(), "candidate backup")

    def test_nonconflicting_account_removal_is_preserved(self):
        for name in ("group", "gshadow"):
            path = self.old / name
            path.write_text("".join(line for line in path.read_text().splitlines(True) if not line.startswith("sudo:")))
        self.merge()
        self.assertNotIn("sudo:", (self.target / "group").read_text())
        self.assertNotIn("sudo:", (self.target / "gshadow").read_text())

    def test_same_name_with_different_gid_is_not_silently_remapped(self):
        self.group(self.old, "docker", 1001, "droid")
        self.group(self.target, "docker", 1002)
        self.assert_conflict_unchanged("both versions changed field 3")

    def test_different_names_cannot_claim_the_same_gid(self):
        self.group(self.old, "docker", 1001, "droid")
        self.group(self.target, "service", 1001)
        self.assert_conflict_unchanged("duplicate numeric identity")

    def test_new_base_cannot_change_existing_uid_while_copying_old_home(self):
        path = self.target / "passwd"
        path.write_text(path.read_text().replace("droid:x:1000:", "droid:x:1001:"))
        self.assert_conflict_unchanged("numeric identity differs")

    def test_deleted_user_id_cannot_be_reassigned_to_new_service(self):
        self.user(self.lower, "olduser", 1001, 1000)
        self.user(self.old, "olduser", 1001, 1000)
        self.user(self.target, "service", 1001, 1000)
        self.assert_conflict_unchanged("numeric identity would belong to another account")

    def test_password_conflict_is_reported_without_values(self):
        path = self.old / "shadow"
        path.write_text(path.read_text().replace("droid:!:", "droid:private-old-value:"))
        path = self.target / "shadow"
        path.write_text(path.read_text().replace("droid:!:", "droid:private-new-value:"))
        with self.assertRaisesRegex(RuntimeError, "shadow.*both versions changed field 2") as error:
            self.merge()
        self.assertNotIn("private-", str(error.exception))

    def test_missing_primary_group_and_inconsistent_shadow_membership_are_rejected(self):
        for database, replacement, reason in (("passwd", ("1000:1000:", "1000:5555:"), "primary group is missing"),
                                               ("gshadow", ("sudo:!::droid", "sudo:!::root"), "membership is inconsistent")):
            with self.subTest(database=database):
                path = self.old / database
                original = path.read_text()
                path.write_text(original.replace(*replacement))
                self.assert_conflict_unchanged(reason)
                path.write_text(original)

    def test_symlink_and_malformed_database_are_rejected_without_writing(self):
        external = self.root / "external"
        external.write_text("sentinel")
        path = self.target / "group"
        original = path.read_text()
        path.unlink()
        path.symlink_to(external)
        self.assert_conflict_unchanged("expected a regular file")
        self.assertEqual(external.read_text(), "sentinel")
        path.unlink()
        path.write_text(original + "root:x:0:\n")
        self.assert_conflict_unchanged("invalid or duplicate")


class GuestUpdatesTest(unittest.TestCase):
    def test_apt_baseline_records_manual_packages_without_recreating_sealed_caches(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for directory in ("var/lib/apt/lists/partial", "var/lib/dpkg", "var/cache/apt",
                              "etc/apt/apt.conf.d", "etc/apt/sources.list.d", "etc/apt/preferences.d"):
                (root / directory).mkdir(parents=True, exist_ok=True)
            (root / "var/lib/dpkg/status").write_text(
                "Package: term-fixture\nStatus: install ok installed\nPriority: optional\n"
                "Section: misc\nInstalled-Size: 1\nMaintainer: TERM\nArchitecture: arm64\n"
                "Version: 1\nDescription: fixture\n\n")
            config = root / "apt.conf"
            config.write_text(
                # Model the ARM64 guest independently of the test host's architecture.
                'APT::Architecture "arm64";\nAPT::Architectures { "arm64"; };\n'
                f'Dir "{root}";\nDir::State "var/lib/apt";\n'
                f'Dir::State::status "{root}/var/lib/dpkg/status";\n'
                'Dir::Cache "var/cache/apt";\nDir::Cache::pkgcache "pkgcache.bin";\n'
                'Dir::Cache::srcpkgcache "srcpkgcache.bin";\nDir::Etc "etc/apt";\n')
            binary = root / "usr/local/lib/droid/droid-sessiond"
            binary.parent.mkdir(parents=True)
            binary.write_bytes(b"guest binary")
            binary.chmod(0o755)
            with patch.dict(os.environ, {"APT_CONFIG": str(config)}):
                packaging.build(root, "debian")
            self.assertEqual((root / "usr/share/term/base-packages").read_text(), "term-fixture\n")
            self.assertEqual(list((root / "var/cache/apt").iterdir()), [])

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
