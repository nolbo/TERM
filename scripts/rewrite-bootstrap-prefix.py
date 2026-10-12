#!/usr/bin/env python3
"""Rewrite Termux bootstrap archives for the com.nolbo.terminal prefix."""

import os
import sys
import tempfile
import zipfile
from pathlib import Path


OLD_PACKAGE = b"com.termux"
NEW_PACKAGE = b"com.nolbo.terminal"
MARKER = ".termux-enhanced-package"
MARKER_VALUE = b"com.nolbo.terminal-v3\n"
ASSET_DIR = Path(__file__).resolve().parents[1] / "app/src/main/assets/termux"
DEB_REWRITER = (ASSET_DIR / "termux-rewrite-deb-prefix").read_bytes()
APT_HOOK = (ASSET_DIR / "00termux-enhanced-prefix").read_bytes()


def rewrite_archive(path: str) -> None:
    with zipfile.ZipFile(path, "r") as source:
        try:
            if source.read(MARKER) == MARKER_VALUE:
                return
        except KeyError:
            pass

        directory = os.path.dirname(os.path.abspath(path))
        descriptor, temporary = tempfile.mkstemp(prefix="bootstrap-rewrite-", suffix=".zip", dir=directory)
        os.close(descriptor)
        try:
            with zipfile.ZipFile(temporary, "w", allowZip64=True) as target:
                target.comment = source.comment
                for info in source.infolist():
                    if info.filename in (
                        MARKER,
                        "bin/termux-rewrite-deb-prefix",
                        "etc/apt/apt.conf.d/00termux-enhanced-prefix",
                    ):
                        continue
                    data = source.read(info.filename).replace(OLD_PACKAGE, NEW_PACKAGE)
                    target.writestr(info, data)

                injected_files = (
                    ("bin/termux-rewrite-deb-prefix", DEB_REWRITER, 0o100755),
                    ("etc/apt/apt.conf.d/00termux-enhanced-prefix", APT_HOOK, 0o100644),
                    (MARKER, MARKER_VALUE, 0o100644),
                )
                for name, data, mode in injected_files:
                    injected = zipfile.ZipInfo(name)
                    injected.compress_type = zipfile.ZIP_DEFLATED
                    injected.external_attr = mode << 16
                    target.writestr(injected, data)

            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: rewrite-bootstrap-prefix.py BOOTSTRAP.zip [...]")
    for archive in sys.argv[1:]:
        rewrite_archive(archive)


if __name__ == "__main__":
    main()
