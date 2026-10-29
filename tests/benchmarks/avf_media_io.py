#!/usr/bin/env python3
"""Measure current bounded media steps; cached host I/O is not AVF disk latency."""
from pathlib import Path
import argparse
import io
import tarfile
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--baseline")
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
cargo = shutil.which("cargo") or str(Path.home() / ".cargo/bin/cargo")
with tempfile.TemporaryDirectory(prefix="term-media-io-") as temporary:
    guest = Path(temporary) / "guest"
    if args.baseline:
        archive = subprocess.check_output(["git", "archive", args.baseline, "guest/droid-sessiond"], cwd=repo)
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(temporary, filter="data")
        guest = Path(temporary) / "guest/droid-sessiond"
    else:
        shutil.copytree(repo / "guest/droid-sessiond", guest, ignore=shutil.ignore_patterns("target"))
    production = (guest / "src/graphics_transport.rs").read_text()
    constructor = "MediaTransfer::new(media, keys)" if "fn new(media: Media" in production else "MediaTransfer { media, keys, first: true }"
    fixture = Path(__file__).with_suffix(".rs").read_text().replace("__TRANSFER__", constructor)
    (guest / "src/media_io_benchmark.rs").write_text(fixture)
    with (guest / "src/graphics_transport.rs").open("a") as output:
        output.write('\n#[cfg(test)] mod io_benchmark { include!("media_io_benchmark.rs"); }\n')
    subprocess.run([cargo, "test", "--offline", "--release", "--manifest-path", str(guest / "Cargo.toml"),
        "graphics_transport::io_benchmark::media_io", "--", "--exact", "--nocapture"], check=True, cwd=repo)
