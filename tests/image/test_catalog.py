import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

REPO = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO / 'image/scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


factory = load('catalog_factory', 'build-avf-image.py')
verifier = load('catalog_verifier', 'verify-avf-release.py')


class CatalogVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'release'
        self.root.mkdir()
        for name in factory.ARTIFACTS.values():
            (self.root / name).write_bytes((name + '\n').encode() * 100)
        self.tag = 'avf-debian-test.1'
        factory.write_release(self.root, self.tag, verifier.PUBLIC_BASE + self.tag + '/', factory.SOURCE_URL, 'a' * 64)

    def change_catalog(self, **changes):
        path = self.root / 'catalog.properties'
        values = verifier.properties(path.read_bytes()) | changes
        path.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))

    def refresh_checksums(self):
        names = ['manifest.properties', 'catalog.properties', 'provenance.json'] + [name + '.gz' for name in verifier.ARTIFACTS.values()]
        (self.root / 'SHA256SUMS').write_text(''.join(f'{factory.sha256(self.root / name)}  {name}\n' for name in names))

    def opener(self, corrupt=None, redirect=None):
        def open_url(url, timeout):
            name = url.rsplit('/', 1)[-1]
            data = (self.root / name).read_bytes()
            if name == corrupt:
                data = b'corrupt'
            response = io.BytesIO(data)
            response.url = redirect or url
            return response
        return mock.Mock(open=mock.Mock(side_effect=open_url))

    def test_factory_and_public_downloads_match_the_same_manifest(self):
        approved = verifier.verify_directory(self.root)
        opener = self.opener()
        verifier.verify_published(approved, opener)
        self.assertEqual(6, opener.open.call_count)
        self.assertEqual(self.tag, approved['releaseId'])
        provenance = json.loads((self.root / 'provenance.json').read_bytes())
        verifier.verify_directory(self.root, expected_commit=provenance['sourceCommit'],
                                  expected_manifest_sha=approved['manifestSha256'])

    def test_private_share_broker_version_is_optional_and_unknown_versions_fail(self):
        current = (self.root / 'manifest.properties').read_bytes()
        for version in [None, b'1', b'0', b'2']:
            data = current.replace(b'shareBrokerVersion=1\n', b'' if version is None else b'shareBrokerVersion=' + version + b'\n')
            approved = {'id': 'debian-arm64-current', 'manifestSha256': verifier.digest(data)}
            if version in (None, b'1'):
                verifier.manifest(data, approved)
            else:
                with self.assertRaisesRegex(ValueError, 'share broker version'):
                    verifier.manifest(data, approved)

    def test_reused_private_share_candidate_requires_real_two_boot_and_owner_loss_evidence(self):
        with self.assertRaisesRegex(ValueError, 'normal-boot evidence'):
            verifier.verify_directory(self.root, metadata_only=True, require_private_shares_boot=True)
        provenance = json.loads((self.root / 'provenance.json').read_bytes())
        report = {'mode': 'normal-systemd', 'transport': 'host-vsock', 'network': 'disabled',
                  'boots': [{'boot': n, 'sessionListRpc': 'passed', 'packageInitializationPort': 'passed'} for n in [1, 2]]}
        report['boots'][1].update(privateShareReadWrite='passed', privateShareOwnerLoss='passed')
        provenance['normalBootVerification'] = report
        (self.root / 'provenance.json').write_text(json.dumps(provenance))
        self.refresh_checksums()
        verifier.verify_directory(self.root, metadata_only=True, require_private_shares_boot=True)
        for field in ['privateShareReadWrite', 'privateShareOwnerLoss', 'sessionListRpc']:
            with self.subTest(field=field):
                report['boots'][1][field] = 'failed'
                (self.root / 'provenance.json').write_text(json.dumps(provenance))
                self.refresh_checksums()
                with self.assertRaisesRegex(ValueError, 'normal-boot evidence'):
                    verifier.verify_directory(self.root, metadata_only=True, require_private_shares_boot=True)
                report['boots'][1][field] = 'passed'

    def test_metadata_transfer_is_checked_against_producer_outputs(self):
        for name in verifier.ARTIFACTS.values():
            (self.root / (name + '.gz')).unlink()
        verifier.verify_directory(self.root, metadata_only=True)
        with self.assertRaisesRegex(ValueError, 'Missing'):
            verifier.verify_directory(self.root)
        with self.assertRaisesRegex(ValueError, 'another source commit'):
            verifier.verify_directory(self.root, metadata_only=True, expected_commit='b' * 40)
        with self.assertRaisesRegex(ValueError, 'producer job'):
            verifier.verify_directory(self.root, metadata_only=True, expected_manifest_sha='b' * 64)

    def test_transport_checksum_alone_cannot_approve_changed_image_content(self):
        (self.root / 'system.img.gz').write_bytes(gzip.compress(b'modified filesystem'))
        self.refresh_checksums()
        with self.assertRaisesRegex(ValueError, 'Image artifact checksum'):
            verifier.verify_directory(self.root)

    def test_metadata_and_manifest_tampering_are_rejected(self):
        (self.root / 'manifest.properties').write_bytes(b'architecture=amd64\n')
        self.refresh_checksums()
        with self.assertRaisesRegex(ValueError, 'pinned catalog'):
            verifier.verify_directory(self.root)

    def test_legacy_manifest_remains_supported_and_unknown_initializer_is_rejected(self):
        current = (self.root / 'manifest.properties').read_bytes().replace(b'androidStorageVersion=1\n', b'')
        for version in [None, b'1', b'2', b'0', b'invalid']:
            with self.subTest(version=version):
                data = current.replace(b'aptInitializationVersion=1\n',
                                       b'' if version is None else b'aptInitializationVersion=' + version + b'\n')
                approved = {'id': 'debian-arm64-current', 'manifestSha256': verifier.digest(data)}
                if version in (None, b'1'):
                    verifier.manifest(data, approved)
                else:
                    with self.assertRaisesRegex(ValueError, 'APT initialization version'):
                        verifier.manifest(data, approved)

    def test_legacy_and_virtiofs_manifest_versions_are_verified(self):
        current = (self.root / 'manifest.properties').read_bytes().replace(b'androidStorageVersion=1\n', b'')
        for version in [None, b'1', b'2', b'0', b'invalid']:
            with self.subTest(version=version):
                data = current.replace(b'virtiofsVersion=1\n',
                                       b'' if version is None else b'virtiofsVersion=' + version + b'\n')
                approved = {'id': 'debian-arm64-current', 'manifestSha256': verifier.digest(data)}
                if version in (None, b'1'):
                    verifier.manifest(data, approved)
                else:
                    with self.assertRaisesRegex(ValueError, 'virtiofs version'):
                        verifier.manifest(data, approved)
        legacy = current.replace(b'virtiofsVersion=1\n', b'').replace(b'aptInitializationVersion=1\n', b'')
        verifier.manifest(legacy, {'manifestSha256': verifier.digest(legacy)})

    def test_android_storage_is_an_optional_supported_feature_with_a_virtiofs_dependency(self):
        current = (self.root / 'manifest.properties').read_bytes()
        for version in [None, b'1', b'2', b'0', b'invalid']:
            with self.subTest(version=version):
                data = current.replace(b'androidStorageVersion=1\n', b'' if version is None else b'androidStorageVersion=' + version + b'\n')
                approved = {'id': 'debian-arm64-current', 'manifestSha256': verifier.digest(data)}
                if version in (None, b'1'):
                    verifier.manifest(data, approved)
                else:
                    with self.assertRaisesRegex(ValueError, 'Android storage version'):
                        verifier.manifest(data, approved)
        missing_dependency = current.replace(b'virtiofsVersion=1\n', b'')
        with self.assertRaisesRegex(ValueError, 'Android storage version'):
            verifier.manifest(missing_dependency, {'manifestSha256': verifier.digest(missing_dependency)})

    def test_private_mutable_or_injected_catalogs_are_rejected(self):
        original = (self.root / 'catalog.properties').read_bytes()
        invalid = [
            {'manifestUri': f'https://github.com/nolbo/terminal/releases/download/{self.tag}/manifest.properties'},
            {'releaseId': 'avf-debian-latest'},
            {'compression': 'none'},
            {'maxArtifactBytes': str(verifier.MAX_BYTES + 1)},
            {'manifestSha256': 'not-a-hash'},
        ]
        for changes in invalid:
            with self.subTest(changes=changes):
                (self.root / 'catalog.properties').write_bytes(original)
                self.change_catalog(**changes)
                with self.assertRaises(ValueError):
                    verifier.catalog((self.root / 'catalog.properties').read_bytes())
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            verifier.catalog(original + b'id=debian-arm64-current\n')
        with self.assertRaisesRegex(ValueError, 'encoding'):
            verifier.properties(b'key=valid\\\ninjected=value\n')

    def test_failed_publication_preserves_existing_default_pin(self):
        output = Path(self.temporary.name) / 'default.properties'
        output.write_bytes(b'existing reviewed pin')
        with mock.patch.object(verifier, 'verify_published', side_effect=ValueError('bad public download')):
            with self.assertRaisesRegex(ValueError, 'bad public download'):
                verifier.approve(self.root, output)
        self.assertEqual(b'existing reviewed pin', output.read_bytes())

    def test_successful_publication_atomically_promotes_the_factory_catalog(self):
        output = Path(self.temporary.name) / 'approved' / 'catalog-approved.properties'
        with mock.patch.object(verifier.urllib.request, 'build_opener', return_value=self.opener()):
            verifier.approve(self.root, output)
        self.assertEqual((self.root / 'catalog.properties').read_bytes(), output.read_bytes())
        self.assertEqual([], list(output.parent.glob('.approved-*')))

    def test_missing_corrupted_or_downgraded_publication_is_never_approved(self):
        approved = verifier.verify_directory(self.root)
        for opener in [self.opener(corrupt='manifest.properties'), self.opener(corrupt='home.img.gz'),
                       self.opener(redirect='http://example.invalid/untrusted')]:
            with self.subTest(opener=opener), self.assertRaises((ValueError, gzip.BadGzipFile)):
                verifier.verify_published(approved, opener)

    def test_compressed_and_expanded_transfer_limits_are_enforced(self):
        with self.assertRaisesRegex(ValueError, 'Downloaded'):
            verifier.BoundedReader(io.BytesIO(b'x' * 101), 100).read()
        with gzip.GzipFile(fileobj=verifier.BoundedReader(io.BytesIO(gzip.compress(b'x' * 1000)), 100)) as stream:
            with self.assertRaisesRegex(ValueError, 'Expanded'):
                verifier.verify_stream(stream, 100, hashlib.sha256(b'x' * 1000).hexdigest())

    def test_https_redirect_policy_allows_signed_cdn_queries_but_no_downgrade_or_credentials(self):
        verifier.https_url('https://release-assets.githubusercontent.com/asset?signature=abc')
        for url in ['http://example.invalid/image', 'https://user:secret@example.invalid/image',
                    'https://example.invalid/image#fragment']:
            with self.assertRaises(ValueError):
                verifier.https_url(url)
            with self.assertRaises(ValueError):
                verifier.HttpsRedirects().redirect_request(None, None, 302, '', {}, url)

    def test_apk_contains_the_exact_reviewed_catalog(self):
        expected = (self.root / 'catalog.properties').read_bytes()
        apk = Path(self.temporary.name) / 'candidate.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('dev/terminal/image/approved-debian.properties', expected)
        verifier.verify_apk(apk, expected)
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('dev/terminal/image/approved-debian.properties', b'# empty\n')
        with self.assertRaisesRegex(ValueError, 'exact approved'):
            verifier.verify_apk(apk, expected)


if __name__ == '__main__':
    unittest.main()
