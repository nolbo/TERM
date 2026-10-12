#!/usr/bin/env python3
"""Verify the release signing identity and, when supplied, approved image catalog."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import zipfile


def verify_certificate(output, expected):
    # SDK tools emit numbered, SDK-range, or scheme-prefixed signer labels.
    certificates = re.findall(
        r"^(?:Signer(?: #\d+| \([^\n]+\))|V2 Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        output, re.MULTILINE,
    )
    if not certificates:
        raise ValueError("No APK signing certificate SHA-256 was parsed")
    digests = {certificate.lower() for certificate in certificates}
    if digests != {expected.lower()}:
        raise ValueError(f"Unexpected release signing certificates: {sorted(digests)}")
    return expected.lower()


def verify_catalog(apk, expected_catalog):
    with zipfile.ZipFile(apk) as archive:
        if archive.read("dev/terminal/image/approved-debian.properties") != expected_catalog:
            raise ValueError("Signed APK does not contain the exact approved image catalog")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    # Imported 0.1.0 stable source predates catalog embedding. Current releases
    # explicitly pass --catalog and retain exact-byte catalog verification.
    parser.add_argument("--catalog", type=Path)
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--apksigner", type=Path)
    args = parser.parse_args()
    signer = args.apksigner
    if signer is None:
        tools = Path(os.environ["ANDROID_HOME"]) / "build-tools"
        signer = max(tools.glob("*/apksigner"), key=lambda path: tuple(int(part) for part in path.parent.name.split(".")))
    result = subprocess.run([str(signer), "verify", "--print-certs", str(args.apk)], check=True, capture_output=True, text=True)
    print(result.stdout, end="")  # Certificate metadata is public; no keystore is read here.
    certificate = verify_certificate(result.stdout, args.certificate_sha256)
    print(f"Release signing certificate verified: {certificate}")
    if args.catalog is not None:
        verify_catalog(args.apk, args.catalog.read_bytes())
        print("Approved AVF catalog verified")


if __name__ == "__main__":
    main()
