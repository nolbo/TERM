#!/usr/bin/env python3
"""Reuse only a successful same-repository AVF kernel with an unchanged recipe.

The factory subsequently verifies every bundle hash and the final kernel config
against the current kernel contract before executing or packaging it.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

RECIPE = ("image/kernel", "image/scripts/build-avf-kernel.py", "image/scripts/avf_kernel.py")


def source_commit(run, repository):
    if (run.get("conclusion") != "success" or run.get("head_branch") != "dev"
            or run.get("path") != ".github/workflows/avf_image.yml"
            or run.get("repository", {}).get("full_name") != repository
            or run.get("head_repository", {}).get("full_name") != repository
            or not re.fullmatch(r"[0-9a-f]{40}", run.get("head_sha", ""))):
        raise ValueError("Kernel reuse requires a successful same-repository dev AVF workflow")
    return run["head_sha"]


def main(run_id):
    if not re.fullmatch(r"[1-9][0-9]*", run_id):
        raise ValueError("Invalid kernel workflow run ID")
    repository = os.environ["GITHUB_REPOSITORY"]
    run = json.loads(subprocess.check_output(["gh", "api", f"repos/{repository}/actions/runs/{run_id}"]))
    commit = source_commit(run, repository)
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, "HEAD"], check=True)
    subprocess.run(["git", "diff", "--exit-code", commit, "HEAD", "--", *RECIPE], check=True)
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write(f"artifact=avf-container-kernel-{commit}\n")
    print(f"Reusing verified kernel producer {run_id} ({commit}); recipe unchanged")


if __name__ == "__main__":
    main(sys.argv[1])
