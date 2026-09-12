"""Build a deterministic, single-file Python application for the Rootless bind."""
import pathlib
import sys
import zipfile

source, destination = map(pathlib.Path, sys.argv[1:])
destination.parent.mkdir(parents=True, exist_ok=True)
with destination.open("wb") as output:
    output.write(b"#!/usr/bin/env python3\n")
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as bundle:
        for file in sorted(source.glob("*.py")):
            name = "__main__.py" if file.name == "terminal-usb.py" else file.name
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            bundle.writestr(info, file.read_bytes())
