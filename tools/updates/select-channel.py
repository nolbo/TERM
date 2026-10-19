#!/usr/bin/env python3
"""Select CI signing/publication inputs without moving either source branch."""
import os
from pathlib import Path
import re
import subprocess
import sys


def select_channel(event: str, ref: str, publish_channel: str = "none") -> tuple[str | None, bool]:
    if publish_channel not in ("none", "main", "dev"):
        raise ValueError("Publication channel must be none, main or dev")
    if publish_channel != "none":
        if event != "workflow_dispatch" or ref != "refs/heads/dev":
            raise ValueError("Explicit channel publication must be dispatched from dev")
        return publish_channel, True
    if event in ("push", "workflow_dispatch") and ref in ("refs/heads/main", "refs/heads/dev"):
        return ref.removeprefix("refs/heads/"), event == "push"
    return None, False


def configure(environment: dict[str, str]) -> None:
    requested = environment.get("TERMINAL_PUBLISH_CHANNEL", "none")
    channel, publish = select_channel(environment["GITHUB_EVENT_NAME"], environment["GITHUB_REF"], requested)
    values = []
    if channel is not None:
        commit = environment["GITHUB_SHA"]
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise ValueError("The checked-out event commit must be a full SHA")
        if requested != "none":
            ancestry = subprocess.run(["git", "merge-base", "--is-ancestor", commit, "refs/remotes/origin/dev"],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
            if ancestry.returncode != 0:
                raise ValueError("The event commit must belong to the fetched shared dev history")
        code = subprocess.check_output([
            sys.executable, str(Path(__file__).with_name("version-code.py")),
            "--run-number", environment["GITHUB_RUN_NUMBER"],
        ], env=environment, text=True).strip()
        values = [f"TERMINAL_UPDATE_CHANNEL={channel}", f"TERMINAL_UPDATE_REVISION={commit}",
                  f"TERMINAL_UPDATE_VERSION_CODE={code}"]
    # Validate the complete request before exposing any signing/publication output.
    with Path(environment["GITHUB_OUTPUT"]).open("a") as output:
        output.write(f"channel={channel or ''}\npublish={str(publish).lower()}\n")
    if values:
        with Path(environment["GITHUB_ENV"]).open("a") as output:
            output.write("\n".join(values) + "\n")


if __name__ == "__main__":
    try:
        configure(dict(os.environ))
    except (KeyError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
