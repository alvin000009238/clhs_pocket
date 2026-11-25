"""Fresh-install launch check for the iOS Release build on a simulator.

Kotlin/Native only runs some initialisers in optimised (Release) binaries
when the code is reached through a particular path, so a Debug UI test run
cannot catch a launch crash that only the archived app has. This installs the
Release app on a clean simulator, launches it without arguments (production
mode) and fails if the process exits within the watch window or never writes
the announcement read state that the first launch creates.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

BUNDLE_ID = "com.clhs.pocket.ios"
READ_STATE = Path("Library/Application Support/PocketData/settings/announcement-read.json")


def simctl(*args: str, check: bool = True) -> str:
    result = subprocess.run(["xcrun", "simctl", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"simctl {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def running_pid(udid: str) -> str | None:
    for line in simctl("spawn", udid, "launchctl", "list").splitlines():
        fields = line.split("\t")
        if len(fields) == 3 and fields[2].startswith(f"UIKitApplication:{BUNDLE_ID}["):
            return fields[0] if fields[0].isdigit() else None
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--udid", required=True)
    parser.add_argument("--app", required=True, type=Path)
    parser.add_argument("--seconds", type=int, default=12)
    args = parser.parse_args()

    simctl("boot", args.udid, check=False)
    simctl("bootstatus", args.udid, "-b")
    simctl("uninstall", args.udid, BUNDLE_ID, check=False)
    simctl("install", args.udid, str(args.app))
    simctl("launch", args.udid, BUNDLE_ID)
    data = Path(simctl("get_app_container", args.udid, BUNDLE_ID, "data").strip())

    deadline = time.monotonic() + args.seconds
    while time.monotonic() < deadline:
        time.sleep(1)
        if running_pid(args.udid) is None:
            sys.exit(f"::error::{BUNDLE_ID} (Release) exited within {args.seconds} s of a fresh launch")
    if not (data / READ_STATE).is_file():
        sys.exit(f"::error::the first launch did not write {READ_STATE}")
    simctl("terminate", args.udid, BUNDLE_ID, check=False)
    print(f"{BUNDLE_ID} (Release) kept running for {args.seconds} s and wrote its announcement read state")


if __name__ == "__main__":
    main()
