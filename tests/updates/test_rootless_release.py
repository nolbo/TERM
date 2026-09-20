import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

PATH = Path(__file__).resolve().parents[2] / 'tools/rootless/prepare-release-runtime.py'
spec = importlib.util.spec_from_file_location('rootless_release', PATH)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RootlessReleaseTest(unittest.TestCase):
    def test_apk_requires_every_exact_runtime_file(self):
        pins = {name: hashlib.sha256(name.encode()).hexdigest() for name in runtime.PAYLOAD}
        with tempfile.TemporaryDirectory() as directory, patch.object(runtime, 'PAYLOAD', pins):
            for broken in (None, 'missing', 'changed'):
                apk = Path(directory) / 'app.apk'
                with zipfile.ZipFile(apk, 'w') as archive:
                    for name in pins:
                        if broken == 'missing' and name == 'libproot-faststat.so':
                            continue
                        entry = {'proot': 'libproot.so', 'proot-loader': 'libproot-loader.so'}.get(name, name)
                        data = b'wrong' if broken == 'changed' and name == 'proot' else name.encode()
                        archive.writestr('lib/arm64-v8a/' + entry, data)
                if broken:
                    with self.assertRaises((KeyError, ValueError)):
                        runtime.verify_apk(apk)
                else:
                    runtime.verify_apk(apk)

    def test_corrupt_download_fails_before_payload_is_written(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets = root / 'assets'
            assets.mkdir()
            for name in runtime.ARCHIVES:
                (assets / name).write_bytes(b'corrupted archive')
            with self.assertRaises(ValueError):
                runtime.prepare(root / 'output', assets)
            self.assertFalse((root / 'output/proot').exists())
