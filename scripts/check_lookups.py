#!/usr/bin/env python3
"""Check that every key=value the BRF tests exists in each BRouter lookup table.

BRouter's BRF parser (BExpressionContext.getLookupValueIdx, 1.7.8 and 1.7.10)
matches a profile value against the *primary* lookup value only; aliases are
used when OSM tags are encoded into RD5 segments, never when a profile is
parsed. `surface=bricks` is an alias of `paved` in the 1.7.10 table and is
therefore rejected by the parser on both tables, so an alias is reported here
as unknown, with the primary value named.
"""
import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / ".tools/brouter-v1.7.8"
DEFAULT_DEPS = ROOT / ".tools/test-deps"
CONTEXT_TAG = "---context:"
LOOKUP = re.compile(r"([A-Za-z0-9_:.-]+)=([^\s()]*)")
ASSIGN = re.compile(r"\bassign\s+([A-Za-z0-9_:.-]+)")
# Hard-coded by BRouter in every lookup table (BExpressionContext.parseMetaLine).
RESERVED = {"way": {"reversedirection": {"yes"}}, "node": {"nodeaccessgranted": {"yes"}}}


def read_lookups(path):
    """Return {context: {key: {"primary": set, "alias": {alias: primary}}}}."""
    tables = {}
    context = None
    for raw in path.read_text(encoding="iso-8859-1").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith(CONTEXT_TAG):
            context = line[len(CONTEXT_TAG):]
            tables.setdefault(context, {})
            continue
        if line.startswith("---") or context is None:
            continue
        tokens = line.split()
        if len(tokens) < 2:
            continue
        key = tokens[0].split(";", 1)[0]
        entry = tables[context].setdefault(key, {"primary": set(), "alias": {}})
        entry["primary"].add(tokens[1])
        for alias in tokens[2:]:
            entry["alias"].setdefault(alias, tokens[1])
    for context, keys in RESERVED.items():
        for key, values in keys.items():
            tables.setdefault(context, {}).setdefault(key, {"primary": set(), "alias": {}})["primary"].update(values)
    return tables


def read_profile(path):
    """Yield (line number, context, key, value) for every lookup test in the BRF."""
    lines = path.read_text(encoding="utf-8").splitlines()
    stripped = [line.split("#", 1)[0] for line in lines]
    assigned = {name for line in stripped for name in ASSIGN.findall(line)}
    context = None
    for number, line in enumerate(stripped, start=1):
        text = line.strip()
        if text.startswith(CONTEXT_TAG):
            context = text[len(CONTEXT_TAG):].strip()
            continue
        for key, values in LOOKUP.findall(line):
            if key in assigned:
                continue
            for value in values.split("|"):
                yield number, context, key, value


def check(profile, lookup_paths, display=None):
    display = display or str(profile)
    problems = []
    pairs = set()
    tables = [(path, read_lookups(path)) for path in lookup_paths]
    for number, context, key, value in read_profile(profile):
        pairs.add((context, key, value))
        for path, table in tables:
            entry = table.get(context, {}).get(key)
            if entry is not None and (value == "" or value in entry["primary"]):
                continue
            if entry is None:
                reason = "unknown key"
            elif value in entry["alias"]:
                reason = f"alias of {entry['alias'][value]}; the BRF parser matches primary values only"
            else:
                reason = None
            message = f"{display}:{number}: {key}={value} not in {path} ({context})"
            problems.append(message + (f" [{reason}]" if reason else ""))
    return problems, len(pairs), len(tables)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--profile", type=Path, default=ROOT / "profiles/gravel-max.brf")
    parser.add_argument("--lookups", type=Path, action="append",
                        help="Lookup table (repeatable); default: the 1.7.8 and 1.7.10 tables under --source/--deps")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="BRouter v1.7.8 checkout")
    parser.add_argument("--deps", type=Path, default=DEFAULT_DEPS, help="Directory holding lookups-v1.7.10.dat")
    args = parser.parse_args()
    lookups = args.lookups or [args.source / "misc/profiles2/lookups.dat", args.deps / "lookups-v1.7.10.dat"]
    for path in [args.profile, *lookups]:
        if not path.is_file():
            parser.error(f"missing file: {path}")
    profile = args.profile.resolve()
    try:
        display = profile.relative_to(ROOT).as_posix()
    except ValueError:
        display = str(args.profile)
    problems, pairs, tables = check(profile, lookups, display)
    for problem in problems:
        print(problem)
    if problems:
        sys.exit(1)
    print(f"OK: {pairs} pairs checked against {tables} tables")


if __name__ == "__main__":
    main()
