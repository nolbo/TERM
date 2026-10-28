#!/usr/bin/env python3
"""Validate a cached kernel without changing the kernel production recipe."""
import argparse
from pathlib import Path

from avf_kernel import validate_bundle


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify all AVF kernel bundle hashes and the current kernel contract")
    parser.add_argument("directory", type=Path)
    metadata = validate_bundle(parser.parse_args().directory)
    print(f"Verified AVF kernel bundle: {metadata['kernelRelease']}")
