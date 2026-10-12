#!/usr/bin/env python3
"""Keep Android workflow versions ordered across repository migrations."""

import argparse
import os
import re

EPOCH = 1_000_000
MAX_VERSION_CODE = 2_147_483_647


def version_code(run_number, offset=0):
    values = []
    for name, value in (("run number", run_number), ("offset", offset)):
        if isinstance(value, bool) or not re.fullmatch(r"[0-9]+", str(value)):
            raise ValueError(f"{name} must be a nonnegative integer")
        values.append(int(value))
    code = EPOCH + sum(values)
    if code > MAX_VERSION_CODE:
        raise ValueError("version code exceeds Android's signed integer range")
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-number", required=True)
    parser.add_argument("--offset", default=os.environ.get("TERMINAL_VERSION_CODE_OFFSET", "0"))
    args = parser.parse_args()
    try:
        print(version_code(args.run_number, args.offset))
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
