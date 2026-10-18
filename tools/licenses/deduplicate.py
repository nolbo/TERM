#!/usr/bin/env python3
"""Build an offline license catalog without changing a single document byte.

The Gradle asset transform supplies a freshly synced output directory. Original
asset paths remain the catalog keys; Java-resource notices retain their complete
META-INF path beneath licenses/java-resources/. Only exact bytes share a blob.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile


def is_java_notice(path):
    parts = PurePosixPath(path).parts
    return (len(parts) > 1 and parts[0] == "META-INF"
            and parts[-1].startswith(("LICENSE", "NOTICE", "COPYING")))


def ant_pattern(pattern):
    """The *, ?, ** path semantics of Android/Gradle resource exclusions."""
    tokens = re.split(r"(\*\*/|\*\*|\*|\?)", pattern.lstrip("/"))
    return re.compile("".join({"**/": "(?:.*/)?", "**": ".*", "*": "[^/]*", "?": "[^/]"}
                              .get(token, re.escape(token)) for token in tokens))


def generate(input_assets, output_assets, java_jars=(), java_directories=(), java_excludes=()):
    documents = {}
    excludes = [ant_pattern(pattern) for pattern in java_excludes]

    def included_java_notice(path):
        return is_java_notice(path) and not any(pattern.fullmatch(path) for pattern in excludes)

    def add(path, body):
        if (not path.startswith("licenses/") or "\\" in path
                or any(part in ("", ".", "..") for part in path.split("/"))):
            raise ValueError(f"Invalid license path: {path}")
        if path in documents and documents[path] != body:
            raise ValueError(f"Conflicting license documents: {path}")
        documents[path] = body

    for source in sorted((input_assets / "licenses").rglob("*")):
        if source.is_symlink():
            raise ValueError(f"License symlinks are not supported: {source}")
        if source.is_file():
            add(source.relative_to(input_assets).as_posix(), source.read_bytes())
    def collect_jar(jar):
        for entry in jar.infolist():
            if not entry.is_dir() and included_java_notice(entry.filename):
                add("licenses/java-resources/" + entry.filename, jar.read(entry))

    for archive in java_jars:
        with zipfile.ZipFile(archive) as jar:
            collect_jar(jar)
            if archive.suffix == ".aar":
                for entry in jar.infolist():
                    if entry.filename == "classes.jar" or (entry.filename.startswith("libs/") and entry.filename.endswith(".jar")):
                        with zipfile.ZipFile(io.BytesIO(jar.read(entry))) as nested:
                            collect_jar(nested)
    for directory in java_directories:
        for source in sorted(directory.rglob("*")):
            path = source.relative_to(directory).as_posix()
            if source.is_file() and included_java_notice(path):
                add("licenses/java-resources/" + path, source.read_bytes())
    if not documents:
        raise ValueError("No bundled license documents found")

    blobs = {}
    entries = []
    for path, body in sorted(documents.items()):
        digest = hashlib.sha256(body).hexdigest()
        # Hashes select candidates, never substitute for full byte equality.
        if digest in blobs and blobs[digest] != body:
            raise ValueError(f"SHA-256 collision for license document: {path}")
        blobs[digest] = body
        entries.append([path, digest])
    for path in documents:
        for parent in PurePosixPath(path).parents:
            if str(parent) in documents:
                raise ValueError(f"License is both file and directory: {parent}")

    # Refuse stale/overlapping output rather than deleting any source documents.
    store = output_assets / "licenses"
    store.mkdir(parents=True)
    (store / "blobs").mkdir()
    for digest, body in sorted(blobs.items()):
        (store / "blobs" / (digest + ".txt")).write_bytes(body)
    (store / "index.json").write_text(
        json.dumps({"version": 1, "documents": entries}, ensure_ascii=True,
                   separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"License catalog: {len(documents)} documents, {len(blobs)} unique bodies; "
          f"{sum(map(len, documents.values())) - sum(map(len, blobs.values()))} "
          "duplicate uncompressed bytes removed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-assets", required=True, type=Path)
    parser.add_argument("--output-assets", required=True, type=Path)
    parser.add_argument("--java-resource-jar", action="append", type=Path, default=[])
    parser.add_argument("--java-resource-directory", action="append", type=Path, default=[])
    parser.add_argument("--java-resource-exclude", action="append", default=[])
    args = parser.parse_args()
    generate(args.input_assets, args.output_assets,
             args.java_resource_jar, args.java_resource_directory, args.java_resource_exclude)


if __name__ == "__main__":
    main()
