#!/usr/bin/env python3
"""Product-side AVF image factory. End users only select an AVF session."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from urllib.parse import urlparse

SOURCE_URL = "https://dl.google.com/android/ferrochrome/latest/aarch64/images.tar.gz"
ARTIFACTS = {"kernel": "vmlinuz", "initrd": "initrd.img", "system": "system.img",
             "home": "home.img", "config": "config.img"}
MAX_BYTES = 32 * 1024 ** 3
REPO = Path(__file__).resolve().parents[2]


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_base_uri(value):
    uri = urlparse(value)
    if (uri.scheme != "https" or not uri.hostname or uri.username or uri.password
            or uri.query or uri.fragment or not value.endswith("/")
            or not re.fullmatch(r"[A-Za-z0-9:/._~-]+", value)):
        raise ValueError("base URI must be an immutable HTTPS release directory ending in /")
    return value


def extract_source(archive, destination):
    """Never mount/extract symlinks, devices, duplicate names or traversal paths."""
    required = {"root_part", "vmlinuz", "initrd.img"}
    seen, total = set(), 0
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not member.isfile():
                raise ValueError("unsafe Google image archive member")
            if str(path) in seen:
                raise ValueError("duplicate Google image archive member")
            seen.add(str(path))
            total += member.size
            if member.size <= 0 or total > MAX_BYTES:
                raise ValueError("source archive exceeds size limit or contains an empty file")
            if str(path) in required:
                with source.extractfile(member) as content, (destination / str(path)).open("xb") as output:
                    shutil.copyfileobj(content, output)
    if not required.issubset(seen):
        raise ValueError("Google archive must contain root_part, vmlinuz and initrd.img (direct-kernel boot)")


def write_release(root, release_id, base_uri, source_url, source_sha, distribution="debian", source_commit=None, boot_verification=None):
    if distribution not in ("debian", "ubuntu", "archlinux"):
        raise ValueError("Unsupported AVF distribution")
    manifest = f"formatVersion=1\ndistribution={distribution}\narchitecture=arm64\npackageInitializationVersion=1\nvirtiofsVersion=1\nandroidStorageVersion=1\n"
    if distribution in ("debian", "ubuntu"):
        manifest += "aptInitializationVersion=1\n"
    for key, name in ARTIFACTS.items():
        artifact = root / name
        if artifact.is_symlink() or not artifact.is_file() or not 0 < artifact.stat().st_size <= MAX_BYTES:
            raise ValueError(f"invalid generated artifact: {name}")
        manifest += f"{key}.sha256={sha256(artifact)}\n"
    (root / "manifest.properties").write_text(manifest)
    limit = max((root / name).stat().st_size for name in ARTIFACTS.values())
    (root / "catalog.properties").write_text(
        f"id={distribution}-arm64-current\nreleaseId={release_id}\n"
        f"manifestUri={base_uri}manifest.properties\n"
        f"manifestSha256={sha256(root / 'manifest.properties')}\n"
        f"maxArtifactBytes={limit}\ncompression=gzip\n")
    (root / "provenance.json").write_text(json.dumps({
        "distribution": distribution, "releaseId": release_id, "sourceUri": source_url, "sourceSha256": source_sha,
        "sourceCommit": source_commit or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
        "rootfs": json.loads((REPO / "image/scripts/avf-distributions.json").read_text()).get(distribution),
        "diskMapping": ["system.img:ro", "home.img:rw", "config.img:ro"],
        "normalBootVerification": boot_verification,
    }, indent=2) + "\n")
    for name in ARTIFACTS.values():
        with (root / name).open("rb") as source, (root / (name + ".gz")).open("xb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=1) as target:
                shutil.copyfileobj(source, target)
    (root / "SHA256SUMS").write_text("".join(
        f"{sha256(root / name)}  {name}\n" for name in
        ["manifest.properties", "catalog.properties", "provenance.json"] + [n + ".gz" for n in ARTIFACTS.values()]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", choices=["debian", "ubuntu", "archlinux"], default="debian")
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--base-uri", required=True, type=validate_base_uri)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source-sha256", help="Optional reviewed upstream checksum; always recorded in provenance")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", args.release_id):
        parser.error("invalid release ID")
    if args.source_sha256 and not re.fullmatch(r"[0-9a-f]{64}", args.source_sha256):
        parser.error("invalid source SHA-256")
    output = args.output.resolve()
    if output.exists():
        parser.error("output already exists; refusing to replace a release")
    output.parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(output.parent).free < 24 * 1024 ** 3:
        parser.error("AVF image production requires at least 24 GiB free space (including Docker layers)")
    boot_check = REPO / 'image/scripts/smoke-avf-systemd.py'
    subprocess.run([sys.executable, str(boot_check), '--check-host'], check=True)
    source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    with tempfile.TemporaryDirectory(prefix=".avf-build-", dir=output.parent) as temporary:
        work = Path(temporary)
        context = work / "context"
        shutil.copytree(REPO / "guest", context / "guest", ignore=shutil.ignore_patterns("target", ".git"))
        shutil.copytree(REPO / "image/scripts", context / "image/scripts", ignore=shutil.ignore_patterns("__pycache__"))
        image_id = work / "docker-image-id"
        subprocess.run(["docker", "build", "--build-arg", f"AVF_DISTRIBUTION={args.distribution}", "--platform", "linux/arm64", "--iidfile", str(image_id), "-f",
                        str(context / "image/scripts/Dockerfile"), str(context)], check=True)
        factory_image = image_id.read_text().strip()
        archive = work / "google-images.tar.gz"
        print("Downloading Google's AVF Debian ARM64 image", flush=True)
        with urllib.request.urlopen(SOURCE_URL, timeout=30) as source, archive.open("xb") as target:
            if urlparse(source.url).scheme != "https" or urlparse(source.url).hostname != "dl.google.com":
                raise ValueError("source download escaped the official Google HTTPS host")
            total = 0
            while chunk := source.read(1024 * 1024):
                total += len(chunk)
                if total > 2 * 1024 ** 3:
                    raise ValueError("Google archive exceeds 2 GiB download limit")
                target.write(chunk)
        source_sha = sha256(archive)
        if args.source_sha256 and source_sha != args.source_sha256:
            raise ValueError("Google source archive checksum mismatch")
        inputs, release = work / "input", work / "release"
        inputs.mkdir()
        release.mkdir()
        extract_source(archive, inputs)
        command = ["docker", "run", "--rm", "--platform", "linux/arm64", "--network", "none",
                   "-v", f"{inputs}:/input:ro", "-v", f"{release}:/output"]
        if Path("/dev/kvm").exists():
            command += ["--device", "/dev/kvm"]
        command += [factory_image]
        try:
            subprocess.run(command, check=True)
        finally:
            # Container creates only this task-owned directory; restore host ownership.
            subprocess.run(["docker", "run", "--rm", "--network", "none", "-v", f"{release}:/output",
                            "--entrypoint", "chown", factory_image,
                            "-R", f"{os.getuid()}:{os.getgid()}", "/output"], check=True)
        boot_report = work / 'normal-systemd-boot.json'
        subprocess.run([sys.executable, str(boot_check), '--directory', str(release),
                        '--docker-image', factory_image, '--report', str(boot_report)], check=True)
        write_release(release, args.release_id, args.base_uri, SOURCE_URL, source_sha, args.distribution,
                      source_commit, json.loads(boot_report.read_text()))
        release.rename(output)
    print(f"Verified release directory: {output}", flush=True)


if __name__ == "__main__":
    main()
