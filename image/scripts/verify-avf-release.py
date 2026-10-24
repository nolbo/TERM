#!/usr/bin/env python3
"""Verify factory artifacts and published images before approving an APK catalog."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import runpy
import tempfile
import urllib.parse
import urllib.request
import zipfile

ARTIFACTS = {'kernel': 'vmlinuz', 'initrd': 'initrd.img', 'system': 'system.img',
             'home': 'home.img', 'config': 'config.img'}
MAX_BYTES = 32 * 1024 ** 3
METADATA_LIMIT = 64 * 1024
PUBLIC_BASE = 'https://github.com/nolbo/TERM/releases/download/'


def properties(data):
    if len(data) > METADATA_LIMIT:
        raise ValueError('Metadata exceeds size limit')
    result = {}
    for line in data.decode('ascii').splitlines():
        if not line or line.startswith('#'):
            continue
        if '\\' in line or '=' not in line:
            raise ValueError('Unsupported properties encoding')
        key, value = line.split('=', 1)
        if key in result or not key or key != key.strip() or value != value.strip():
            raise ValueError('Duplicate or noncanonical properties')
        result[key] = value
    return result


def digest(data):
    return hashlib.sha256(data).hexdigest()


def release_id(value):
    if not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,63}', value) or 'latest' in value or 'current' in value:
        raise ValueError('An immutable release ID is required')
    return value


def catalog(data):
    approved = properties(data)
    required = {'id', 'releaseId', 'manifestUri', 'manifestSha256', 'maxArtifactBytes', 'compression'}
    if not required <= set(approved) or set(approved) - required - {'maintenanceVersion'} or approved['id'] not in {d + '-arm64-current' for d in ('debian', 'ubuntu', 'archlinux')}:
        raise ValueError('Invalid approved catalog fields')
    tag = release_id(approved['releaseId'])
    if 'maintenanceVersion' in approved and approved['maintenanceVersion'] != '1':
        raise ValueError('Unsupported catalog maintenance version')
    if approved['manifestUri'] != f'{PUBLIC_BASE}{tag}/manifest.properties':
        raise ValueError('Catalog must use the public immutable TERM release URL')
    if not re.fullmatch(r'[0-9a-f]{64}', approved['manifestSha256']):
        raise ValueError('Invalid pinned manifest hash')
    if not approved['maxArtifactBytes'].isdigit() or not 0 < int(approved['maxArtifactBytes']) <= MAX_BYTES:
        raise ValueError('Invalid artifact size limit')
    if approved['compression'] != 'gzip':
        raise ValueError('Factory artifact compression must be gzip')
    return approved


def catalog_entries(data):
    values = properties(data)
    if not values:
        return {}
    if 'id' in values:
        entry = catalog(data)
        return {entry['id'].removesuffix('-arm64-current'): entry}
    if values.get('formatVersion') != '2':
        raise ValueError('Unsupported catalog collection')
    ids = values.get('distributions', '').split(',')
    if len(ids) != len(set(ids)) or any(d not in ('debian', 'ubuntu', 'archlinux') for d in ids):
        raise ValueError('Invalid catalog distributions')
    entries = {}
    for distribution in ids:
        entry = {key.removeprefix(distribution + '.'): value for key, value in values.items() if key.startswith(distribution + '.')}
        entries[distribution] = catalog(''.join(f'{key}={value}\n' for key, value in entry.items()).encode('ascii'))
        if entries[distribution]['id'] != distribution + '-arm64-current':
            raise ValueError('Catalog distribution mismatch')
    allowed = {'formatVersion', 'distributions'} | {d + '.' + k for d, entry in entries.items() for k in entry}
    if set(values) != allowed:
        raise ValueError('Unknown catalog entries')
    return entries


def merge_catalog(previous, new):
    entries = catalog_entries(previous)
    entries.update(catalog_entries(new))
    if len(entries) == 1:
        return ''.join(f'{k}={v}\n' for k, v in next(iter(entries.values())).items()).encode('ascii')
    ids = [d for d in ('debian', 'ubuntu', 'archlinux') if d in entries]
    contents = 'formatVersion=2\ndistributions=' + ','.join(ids) + '\n'
    contents += ''.join(f'{d}.{key}={value}\n' for d in ids for key, value in entries[d].items())
    return contents.encode('ascii')


def manifest(data, approved):
    if digest(data) != approved['manifestSha256']:
        raise ValueError('Manifest differs from the pinned catalog hash')
    values = properties(data)
    required = {'formatVersion', 'distribution', 'architecture'} | {key + '.sha256' for key in ARTIFACTS}
    optional = {'aptInitializationVersion', 'packageInitializationVersion', 'virtiofsVersion', 'androidStorageVersion', 'shareBrokerVersion', 'maintenanceVersion', 'sessionResumeVersion'}
    if (not required <= set(values) or not set(values) <= required | optional
            or values['formatVersion'] not in ('1', '2')
            or (values['distribution'], values['architecture']) != (approved.get('id', 'debian-arm64-current').removesuffix('-arm64-current'), 'arm64')):
        raise ValueError('Expected catalog-selected Linux ARM64 image manifest v1 or v2')
    if 'aptInitializationVersion' in values and (values['aptInitializationVersion'] != '1' or values['distribution'] == 'archlinux'):
        raise ValueError('Unsupported APT initialization version')
    if 'packageInitializationVersion' in values and values['packageInitializationVersion'] != '1':
        raise ValueError('Unsupported package initialization version')
    if 'virtiofsVersion' in values and values['virtiofsVersion'] != '1':
        raise ValueError('Unsupported virtiofs version')
    if 'androidStorageVersion' in values and (values['androidStorageVersion'] != '1' or values.get('virtiofsVersion') != '1'):
        raise ValueError('Unsupported Android storage version')
    if 'shareBrokerVersion' in values and values['shareBrokerVersion'] not in ('1', '2'):
        raise ValueError('Unsupported private share broker version')
    if 'maintenanceVersion' in values and values['maintenanceVersion'] != '1':
        raise ValueError('Unsupported guest maintenance version')
    if 'sessionResumeVersion' in values and values['sessionResumeVersion'] != '1':
        raise ValueError('Unsupported session resume version')
    if any(not re.fullmatch(r'[0-9a-f]{64}', values[key + '.sha256']) for key in ARTIFACTS):
        raise ValueError('Invalid artifact hash')
    return values


def read_metadata(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > METADATA_LIMIT:
        raise ValueError(f'Invalid metadata file: {path.name}')
    return path.read_bytes()


def verify_stream(stream, limit, expected):
    size = 0
    checksum = hashlib.sha256()
    while chunk := stream.read(min(1024 * 1024, limit - size + 1)):
        size += len(chunk)
        if size > limit:
            raise ValueError('Expanded image artifact exceeds its limit')
        checksum.update(chunk)
    if not size or checksum.hexdigest() != expected:
        raise ValueError('Image artifact checksum mismatch or empty artifact')


class BoundedReader:
    def __init__(self, stream, limit):
        self.stream, self.limit, self.size = stream, limit, 0

    def read(self, size=-1):
        size = min(size if size >= 0 else self.limit + 1, self.limit - self.size + 1)
        chunk = self.stream.read(size)
        self.size += len(chunk)
        if self.size > self.limit:
            raise ValueError('Downloaded image artifact exceeds its limit')
        return chunk


def verify_directory(root, metadata_only=False, expected_commit=None, expected_manifest_sha=None,
                     require_private_shares_boot=False, require_container_kernel=False):
    approved = catalog(read_metadata(root / 'catalog.properties'))
    if expected_manifest_sha is not None and approved['manifestSha256'] != expected_manifest_sha:
        raise ValueError('Catalog differs from the producer job manifest hash')
    values = manifest(read_metadata(root / 'manifest.properties'), approved)
    provenance = json.loads(read_metadata(root / 'provenance.json'))
    if provenance['releaseId'] != approved['releaseId'] or not re.fullmatch(r'[0-9a-f]{40}', provenance['sourceCommit']):
        raise ValueError('Invalid source provenance')
    if expected_commit is not None and provenance['sourceCommit'] != expected_commit:
        raise ValueError('Image came from another source commit')
    if not re.fullmatch(r'[0-9a-f]{64}', provenance['sourceSha256']) or provenance['diskMapping'] != ['system.img:ro', 'home.img:rw', 'config.img:ro']:
        raise ValueError('Invalid source image hash or disk mapping')
    if values['formatVersion'] == '2' and provenance.get('rootFilesystem') != 'ext4':
        raise ValueError('Image v2 requires ext4 root provenance')
    if provenance.get('distribution', 'debian') != values['distribution']:
        raise ValueError('Rootfs provenance distribution differs from manifest')
    kernel_build = provenance.get('kernelBuild')
    if require_container_kernel and not kernel_build:
        raise ValueError('Missing built container kernel provenance')
    if kernel_build:
        kernel = runpy.run_path(str(Path(__file__).with_name('avf_kernel.py')))
        kernel['validate_provenance'](kernel_build, values['kernel.sha256'])
        kernel['validate_boot_report'](provenance.get('containerBootVerification'))
    if require_private_shares_boot:
        report = provenance.get('normalBootVerification') or {}
        boots = report.get('boots') or []
        if (values.get('shareBrokerVersion') not in ('1', '2') or report.get('mode') != 'normal-systemd'
                or report.get('transport') != 'host-vsock' or report.get('network') != 'disabled'
                or len(boots) != 2 or [boot.get('boot') for boot in boots] != [1, 2]
                or any(boot.get('sessionListRpc') != 'passed' or boot.get('packageInitializationPort') != 'passed'
                       for boot in boots)
                or boots[1].get('privateShareReadWrite') != 'passed'
                or boots[1].get('privateShareOwnerLoss') != 'passed'):
            raise ValueError('Missing verified private-share normal-boot evidence')
        if values.get('shareBrokerVersion') == '2' and any(
                boots[1].get(key) != 'passed' for key in ('privateShareReconnect', 'privateShareGuard')):
            raise ValueError('Missing verified private-share recovery evidence')
        if values.get('sessionResumeVersion') == '1' and any(boot.get('sessionResume') != 'passed' for boot in boots):
            raise ValueError('Missing verified session recovery evidence')
    if values['distribution'] != 'debian':
        profile = json.loads(Path(__file__).with_name('avf-distributions.json').read_text())[values['distribution']]
        if provenance.get('rootfs') != profile:
            raise ValueError('Rootfs provenance differs from the approved distribution source')
    checksums = {}
    for line in read_metadata(root / 'SHA256SUMS').decode('ascii').splitlines():
        checksum, name = line.split('  ', 1)
        if not re.fullmatch(r'[0-9a-f]{64}', checksum) or name in checksums:
            raise ValueError('Invalid publication checksums')
        checksums[name] = checksum
    names = {'catalog.properties', 'manifest.properties', 'provenance.json'} | {name + '.gz' for name in ARTIFACTS.values()}
    if set(checksums) != names:
        raise ValueError('Incomplete publication checksums')
    for name in names:
        if name.endswith('.gz') and metadata_only:
            continue
        path = root / name
        if path.is_symlink() or not path.is_file() or (name.endswith('.gz') and path.stat().st_size > int(approved['maxArtifactBytes'])):
            raise ValueError(f'Missing or unsafe image artifact: {name}')
        with path.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != checksums[name]:
                raise ValueError(f'Publication checksum mismatch: {name}')
    if not metadata_only:
        for key, name in ARTIFACTS.items():
            with (root / (name + '.gz')).open('rb') as raw, gzip.GzipFile(fileobj=BoundedReader(raw, int(approved['maxArtifactBytes']))) as stream:
                verify_stream(stream, int(approved['maxArtifactBytes']), values[key + '.sha256'])
    return approved


def https_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise ValueError('Only credential-free HTTPS downloads are allowed')
    return url


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 5

    def redirect_request(self, request, fp, code, message, headers, url):
        return super().redirect_request(request, fp, code, message, headers, https_url(url))


def verify_published(approved, opener=None):
    opener = opener or urllib.request.build_opener(HttpsRedirects())
    with opener.open(https_url(approved['manifestUri']), timeout=60) as response:
        https_url(response.url)
        data = response.read(METADATA_LIMIT + 1)
    values = manifest(data, approved)
    for key, name in ARTIFACTS.items():
        url = urllib.parse.urljoin(approved['manifestUri'], name + '.gz')
        with opener.open(https_url(url), timeout=60) as response:
            https_url(response.url)
            with gzip.GzipFile(fileobj=BoundedReader(response, int(approved['maxArtifactBytes']))) as stream:
                verify_stream(stream, int(approved['maxArtifactBytes']), values[key + '.sha256'])


def approve(root, output, **verification):
    approved = verify_directory(root, metadata_only=True, **verification)
    verify_published(approved)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix='.approved-', delete=False) as temporary:
        temporary.write(merge_catalog(read_metadata(output) if output.exists() else b'', read_metadata(root / 'catalog.properties')))
        staged = Path(temporary.name)
    try:
        staged.replace(output)
    finally:
        staged.unlink(missing_ok=True)
    return approved


def verify_apk(path, expected_catalog):
    catalog_entries(expected_catalog)
    with zipfile.ZipFile(path) as apk:
        actual = apk.read('dev/terminal/image/approved-debian.properties')
    if actual != expected_catalog:
        raise ValueError('APK did not embed the exact approved image catalog')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--metadata-only', action='store_true')
    parser.add_argument('--expected-commit')
    parser.add_argument('--expected-manifest-sha')
    parser.add_argument('--require-private-shares-boot', action='store_true')
    parser.add_argument('--require-container-kernel', action='store_true')
    parser.add_argument('--approve', type=Path, help='Verify public downloads before writing the tracked default catalog')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--apk-catalog', type=Path, help='Exact merged catalog expected inside the candidate APK')
    args = parser.parse_args()
    checks = dict(expected_commit=args.expected_commit, expected_manifest_sha=args.expected_manifest_sha,
                  require_private_shares_boot=args.require_private_shares_boot,
                  require_container_kernel=args.require_container_kernel)
    approved = approve(args.directory, args.approve, **checks) if args.approve else verify_directory(args.directory, args.metadata_only, **checks)
    if args.apk:
        verify_apk(args.apk, read_metadata(args.apk_catalog or args.directory / 'catalog.properties'))
    print(f"Verified AVF release {approved['releaseId']}: {approved['manifestSha256']}")


if __name__ == '__main__':
    main()
