#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Inventory one runtime revision against preprocessed AArch64 NDK macros.

Routes describe dispatch coverage, not complete Linux semantics or Android policy.
See COMPATIBILITY_AUDIT.md for the exact snapshot and preprocessing command.
"""
import argparse
import collections
import json
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("guest", type=Path)
    parser.add_argument("macros", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    macros = dict(re.findall(r"^#define (\w+) (.+)$", args.macros.read_text(), re.M))

    def number(value):
        seen = set()
        while value in macros:
            if value in seen:
                raise ValueError(f"cyclic macro: {value}")
            seen.add(value)
            value = macros[value]
        return int(value, 0)

    calls = {name[4:]: number(value) for name, value in macros.items()
             if name.startswith("SYS_") and name != "SYS_syscalls"}
    source = args.guest.read_text()
    handler = source[source.index("static long handle"):source.index("static void trace_number")]
    direct = set(re.findall(r"DIRECT\(SYS_(\w+)\)", source))
    conditional = set(re.findall(r"DIRECT_NULL\(SYS_(\w+),", source))
    handled = set(re.findall(r"case SYS_(\w+):", handler))
    forwarded = set(re.findall(r"case SYS_(\w+):", handler[handler.index("case SYS_read:"):]))
    rejected = {"execveat", "sigaltstack"}
    if not rejected <= handled or not calls or (direct | conditional | handled) - calls.keys():
        raise ValueError("source/macros changed; review classifier before updating the inventory")
    counts = collections.Counter()
    rows = ["number\tsyscall\troute\n"]
    for name, nr in sorted(calls.items(), key=lambda item: (item[1], item[0])):
        route = ("direct" if name in direct else
                 "conditional-direct" if name in conditional else
                 "explicit-EOPNOTSUPP" if name in rejected else
                 "forwarded" if name in forwarded else
                 "handled" if name in handled else "ENOSYS")
        counts[route] += 1
        rows.append(f"{nr}\t{name}\t{route}\n")
    args.output.write_text("".join(rows))
    print(json.dumps({"total": len(calls), "routes": counts}, sort_keys=True))


if __name__ == "__main__":
    main()
