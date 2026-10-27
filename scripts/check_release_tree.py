#!/usr/bin/env python3
"""Verify that a release tree's committed evidence matches its files."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

try:
    from scripts.build_profile_release import REQUIRED_CHECKS
except ImportError:
    from build_profile_release import REQUIRED_CHECKS

EXCLUDED_PARTS = (".git", "output", "__pycache__")
FRESH_KEYS = ("profile_sha256", "test_source_sha256", "brouter_revision", "dependency_sha256", "lookups_sha256")


class NotReleaseTree(Exception):
    pass


class EvidenceMismatch(Exception):
    pass


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tree_files(root):
    """List tracked files only when root is itself the git toplevel; an extracted tree inside another repo must be walked."""
    try:
        top = subprocess.run(["git", "-C", str(root), "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=True).stdout.strip()
        if Path(top).resolve() == root.resolve():
            listed = subprocess.run(["git", "-C", str(root), "ls-files", "-z"],
                                    capture_output=True, text=True, check=True).stdout
            names = [n for n in listed.split("\0") if n and (root / n).is_file()]
            return sorted(n for n in names if not excluded(n))
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    names = (p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file())
    return sorted(n for n in names if not excluded(n))


def excluded(name):
    return name == "SHA256SUMS" or any(part in EXCLUDED_PARTS for part in Path(name).parts[:-1]) \
        or Path(name).parts[0] in EXCLUDED_PARTS


def check(root, fresh=None):
    root = Path(root)
    sums_path = root / "SHA256SUMS"
    validation_path = root / "validation/validation.json"
    if not sums_path.is_file() or not validation_path.is_file():
        raise NotReleaseTree("not a release tree (no SHA256SUMS / validation/validation.json)")
    report = json.loads(validation_path.read_text())

    profile = root / "profiles/gravel-max.brf"
    if report.get("profile_sha256") != sha(profile):
        raise EvidenceMismatch("profile_sha256 does not match profiles/gravel-max.brf")
    for name, expected in sorted(report.get("test_source_sha256", {}).items()):
        path = root / name
        if not path.is_file() or sha(path) != expected:
            raise EvidenceMismatch(f"test_source_sha256 does not match {name}")
    statuses = {entry.get("name"): entry.get("status") for entry in report.get("checks", [])}
    for name in REQUIRED_CHECKS:
        if statuses.get(name) != "passed":
            raise EvidenceMismatch(f"Required validation check missing or failed: {name}")

    listed = {}
    for line in sums_path.read_text().splitlines():
        digest_, name = line.split("  ", 1)
        listed[name] = digest_
        path = root / name
        if not path.is_file() or sha(path) != digest_:
            raise EvidenceMismatch(f"SHA256SUMS does not match {name}")
    for name in tree_files(root):
        if name not in listed:
            raise EvidenceMismatch(f"SHA256SUMS does not list {name}")

    if fresh is not None:
        new = json.loads(Path(fresh).read_text())
        for key in FRESH_KEYS:
            if new.get(key) != report.get(key):
                raise EvidenceMismatch(f"fresh validation differs from committed in {key}")
        summarize = lambda r: [(c.get("name"), c.get("status"), c.get("summary")) for c in r.get("checks", [])]
        if summarize(new) != summarize(report):
            raise EvidenceMismatch("fresh validation differs from committed in checks")
    return f"OK: {len(listed)} files match SHA256SUMS; validation evidence is consistent" + (
        " with the fresh run" if fresh is not None else "")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--fresh", type=Path, help="Freshly generated validation.json to compare")
    args = parser.parse_args()
    try:
        print(check(args.root, args.fresh))
    except NotReleaseTree as error:
        print(error, file=sys.stderr)
        sys.exit(2)
    except EvidenceMismatch as error:
        print(f"MISMATCH: {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
