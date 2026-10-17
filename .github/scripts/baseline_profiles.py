"""Reject missing or unusable saved release profiles before signing."""

import argparse
from pathlib import Path
import re


APP_RULE = re.compile(r"[HSP]*Lcom/clhs/score/[^;\s]+;(?:->[^\s(]+\([^\s)]*\)\S+)?")


def validate_profiles(directory: Path) -> None:
    for name in ("baseline-prof.txt", "startup-prof.txt"):
        path = directory / name
        lines = path.read_text(encoding="utf-8").splitlines()
        if not any(APP_RULE.fullmatch(line.strip()) for line in lines):
            raise ValueError(f"{path}: expected at least one com.clhs.score profile rule")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        validate_profiles(args.directory)
    except (OSError, UnicodeError, ValueError) as error:
        parser.exit(1, f"{error}\n")
