#!/usr/bin/env python3
"""Compare an original APK with a catalog APK, including every notice's bytes."""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from deduplicate import is_java_notice


def verify(before_path, after_path):
    with zipfile.ZipFile(before_path) as before, zipfile.ZipFile(after_path) as after:
        originals = {}
        before_license_paths = set()
        for name in before.namelist():
            if name.endswith("/"):
                continue
            if name.startswith("assets/licenses/"):
                path = name.removeprefix("assets/")
            elif is_java_notice(name):
                path = "licenses/java-resources/" + name
            else:
                continue
            originals[path] = before.read(name)
            before_license_paths.add(name)
        index = json.loads(after.read("assets/licenses/index.json"))
        assert index["version"] == 1, "Unsupported catalog version"
        entries = dict(index["documents"])
        assert len(entries) == len(index["documents"]), "Duplicate document paths"
        assert set(originals) <= set(entries), "Missing original license aliases"
        blobs = {}
        for path, digest in entries.items():
            name = f"assets/licenses/blobs/{digest}.txt"
            body = after.read(name)
            assert hashlib.sha256(body).hexdigest() == digest, f"Corrupt blob: {path}"
            if path in originals:
                assert body == originals[path], f"Changed notice/copyright/license bytes: {path}"
            else:
                # AARs can carry component-specific aliases for the same notices
                # previously bundled only by their JVM dependencies. Expose them
                # without adding any new body or weakening the original comparison.
                assert path.startswith("licenses/java-resources/"), f"Unexpected added document: {path}"
                assert body in originals.values(), f"Unexpected additional license body: {path}"
            blobs[name] = body
        assert len(set(blobs.values())) == len(blobs), "Duplicate physical bodies remain"
        after_license_paths = {n for n in after.namelist() if n.startswith("assets/licenses/") and not n.endswith("/")}
        assert after_license_paths == set(blobs) | {"assets/licenses/index.json"}, "Unreferenced license assets"
        # In particular, do not leave duplicate Java notices behind or remove
        # unrelated assets, service descriptors, metadata, or native payloads.
        assert not any(is_java_notice(n) for n in after.namelist()), "Java notice stored twice"
        def other_payloads(archive, license_paths):
            # Code changes necessarily regenerate DEX layout/profile metadata.
            generated_profiles = {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}
            return {n: archive.read(n) for n in archive.namelist()
                    if n not in license_paths and n not in generated_profiles and not n.endswith("/")
                    and n.startswith(("assets/", "lib/", "META-INF/"))}
        assert other_payloads(before, before_license_paths) == other_payloads(after, after_license_paths), "Unrelated payload changed"
        result = {
            "documents_verified_byte_for_byte": len(originals),
            "catalog_document_aliases": len(entries),
            "additional_component_notice_aliases": len(entries) - len(originals),
            "unique_bodies": len(blobs),
            "duplicate_documents_removed": len(originals) - len(blobs),
            "uncompressed_body_bytes_before": sum(map(len, originals.values())),
            "uncompressed_body_bytes_after": sum(map(len, blobs.values())),
            "compressed_license_bytes_before": sum(before.getinfo(n).compress_size for n in before_license_paths),
            "compressed_license_bytes_after_including_index": sum(after.getinfo(n).compress_size for n in after_license_paths),
            "apk_bytes_before": before_path.stat().st_size,
            "apk_bytes_after": after_path.stat().st_size,
            "apk_bytes_saved": before_path.stat().st_size - after_path.stat().st_size,
        }
        result["apk_percent_saved"] = round(100 * result["apk_bytes_saved"] / result["apk_bytes_before"], 3)
        return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.before, args.after), indent=2))
