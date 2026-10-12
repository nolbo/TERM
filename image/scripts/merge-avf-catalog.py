#!/usr/bin/env python3
"""Merge verified candidate pins without dropping the other AVF distributions."""
import argparse
from pathlib import Path
import runpy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--existing', required=True, type=Path)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    verifier = runpy.run_path(str(Path(__file__).with_name('verify-avf-release.py')))
    previous = verifier['read_metadata'](args.existing) if args.existing.exists() else b''
    contents = verifier['merge_catalog'](previous, verifier['read_metadata'](args.candidate))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(contents)


if __name__ == '__main__':
    main()
