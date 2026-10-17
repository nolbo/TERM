#!/usr/bin/env python3
"""Allocate increasing debug APK versions independently of signed releases."""

import argparse
from pathlib import Path
import re
import sqlite3
import time

EPOCH = 1_577_836_800  # 2020-01-01 00:00:00 UTC; fits Android's range until 2088.
MAX_VERSION_CODE = 2_147_483_647


def nonnegative_integer(value):
    if isinstance(value, bool) or not re.fullmatch(r"[0-9]+", str(value)):
        raise ValueError("version inputs must be nonnegative integers")
    return int(value)


def debug_version_code(timestamp, previous=0, minimum=0):
    timestamp, previous, minimum = map(nonnegative_integer, (timestamp, previous, minimum))
    if previous == 0 and timestamp < EPOCH:
        raise ValueError("correct the system clock before the first debug build")
    code = max(timestamp - EPOCH, previous + 1, minimum + 1)
    if code > MAX_VERSION_CODE:
        raise ValueError("debug version code exceeds Android's signed integer range")
    return code


def reserve_debug_version(state_file, minimum=0, timestamp=None):
    state_file = Path(state_file)
    state_file.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(state_file, timeout=30) as db:
        db.execute("CREATE TABLE IF NOT EXISTS version (id INTEGER PRIMARY KEY CHECK (id = 1), code INTEGER NOT NULL)")
        db.execute("BEGIN IMMEDIATE")
        row = db.execute("SELECT code FROM version WHERE id = 1").fetchone()
        code = debug_version_code(int(time.time()) if timestamp is None else timestamp,
                                  previous=row[0] if row else 0, minimum=minimum)
        db.execute("INSERT INTO version (id, code) VALUES (1, ?) "
                   "ON CONFLICT(id) DO UPDATE SET code = excluded.code", (code,))
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--minimum", default="0", help="installed or previously allocated version code to exceed")
    args = parser.parse_args()
    try:
        code = reserve_debug_version(args.state_file, args.minimum)
    except (OSError, sqlite3.Error, ValueError) as error:
        parser.error(str(error))
    print(code)


if __name__ == "__main__":
    main()
