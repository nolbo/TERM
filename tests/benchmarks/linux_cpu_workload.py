#!/usr/bin/env python3
"""Run identical CPU work inside AVF and Userspace Linux with explicit CPU parity.

Usage: python3 linux_cpu_workload.py --cpus 1 --workers 4 --rounds 5
Run again with --cpus 4 on both environments after selecting host CPUs for AVF.
Affinity, not a claimed AVF configuration, is recorded. No setting is changed.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import resource
import time


def work(blocks):
    block = bytes(1024 * 1024)
    start = time.process_time()
    result = None
    for _ in range(blocks):
        result = hashlib.sha256(block).hexdigest()
    return result, time.process_time() - start, resource.getrusage(resource.RUSAGE_SELF).ru_maxrss


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cpus", type=int, required=True)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--blocks", type=int, default=512)
    args = parser.parse_args()
    allowed = sorted(os.sched_getaffinity(0))
    if not 1 <= args.cpus <= len(allowed) or min(args.workers, args.rounds, args.blocks) < 1:
        parser.error("positive workloads and an available CPU count are required")
    os.sched_setaffinity(0, allowed[:args.cpus])
    for run in range(args.rounds):
        start = time.monotonic()
        with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
            results = list(pool.map(work, [args.blocks] * args.workers))
        assert len({r[0] for r in results}) == 1
        print(json.dumps(dict(run=run + 1, available_cpus=len(allowed), affinity_cpus=args.cpus,
            workers=args.workers, total_mib=args.blocks * args.workers,
            wall_seconds=time.monotonic() - start, worker_cpu_seconds=sum(r[1] for r in results),
            max_worker_rss_kib=max(r[2] for r in results))), flush=True)


if __name__ == "__main__":
    main()
