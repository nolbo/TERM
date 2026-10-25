import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]

class AvfNoSharesTest(unittest.TestCase):
    def test_session_install_has_no_mount_services_or_sessiond_dependencies(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(['sh', str(REPO / 'image/scripts/configure-avf-session.sh'), str(root),
                            str(REPO / 'image/scripts')], check=True)
            self.assertTrue((root / 'usr/local/lib/droid/session-recovery.py').is_file())
            self.assertTrue((root / 'usr/local/lib/droid/private-share-transport.py').is_file())
            units = root / 'etc/systemd/system'
            self.assertEqual(['term-session-recovery.service'], sorted(p.name for p in units.glob('*.service')))
            self.assertFalse((units / 'droid-sessiond.service.d').exists())
            self.assertTrue((units / 'multi-user.target.wants/term-session-recovery.service').is_symlink())

    def test_maintenance_retires_only_term_services_and_preserves_data(self):
        spec = importlib.util.spec_from_file_location('migrate_retired_shares', REPO / 'image/scripts/migrate-avf-state.py')
        migrate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(migrate)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = ['etc/systemd/system/term-shares.service',
                     'etc/systemd/system/droid-sessiond.service.d/shares.conf',
                     'etc/systemd/system/droid-sessiond.service.d/android-storage.conf',
                     'usr/local/lib/droid/private-share-broker.py']
            for name in paths + ['home/droid/project/keep', 'etc/fuse.conf']:
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('keep')
            wants = root / 'etc/systemd/system/multi-user.target.wants'
            wants.mkdir()
            (wants / 'term-shares.service').symlink_to('/etc/systemd/system/term-shares.service')
            migrate.retire_shared_directories(root)
            self.assertTrue(all(not (root / name).exists() for name in paths))
            self.assertFalse((wants / 'term-shares.service').is_symlink())
            self.assertEqual('keep', (root / 'home/droid/project/keep').read_text())
            self.assertEqual('keep', (root / 'etc/fuse.conf').read_text())

    def test_maintenance_rejects_redirected_service_parents(self):
        spec = importlib.util.spec_from_file_location('migrate_retired_shares', REPO / 'image/scripts/migrate-avf-state.py')
        migrate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(migrate)
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = parent / 'root'
            outside = parent / 'outside'
            outside.mkdir()
            (outside / 'term-shares.service').write_text('keep')
            (root / 'etc/systemd').mkdir(parents=True)
            (root / 'etc/systemd/system').symlink_to(outside, target_is_directory=True)
            with self.assertRaises(RuntimeError):
                migrate.retire_shared_directories(root)
            self.assertEqual('keep', (outside / 'term-shares.service').read_text())
