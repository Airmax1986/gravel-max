#!/usr/bin/env python3
"""Compile pinned BRouter and run real expression and RD5-routing contracts."""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = "041488b36c761ff2dd0fd71c1538b646364223ea"
DEPENDENCIES = {
    "osmosis-osm-binary-0.48.3.jar": (
        "org/openstreetmap/osmosis/osmosis-osm-binary/0.48.3/",
        "06538f38520151c3b4d32d05311038509c10be5b43c2534c4d127dfe698bf2eb"),
    "protobuf-java-3.12.2.jar": (
        "com/google/protobuf/protobuf-java/3.12.2/",
        "b385bef93bad20c5e7a6cf3b1c73edde29bdec4bb3bb75688d233b561f0dcaa0"),
}
# Lookup tables at or above lookupversion 11 (BRouter 1.7.9+, brouter.de) dropped
# values that 1.7.8 still has; a profile referencing one fails to parse there.
LOOKUPS = {
    "lookups-v1.7.10.dat": (
        "https://raw.githubusercontent.com/abrensch/brouter/"
        "4d2639af77ea5ed9c30d3e400764eb6f9e8522da/misc/profiles2/lookups.dat",
        "bf8fc3b2c052b7204e9c5a8d78e4d6ab6b125d3236a943f34583b838c477f419"),
}

# Golden cost matrix: every archetype under the default options and with each option
# flipped once. Ordering contracts let wrong magnitudes through (a cheap trunk, a
# forest bonus below plain cost); any cost change now fails until
# tests/brouter/expected-costs.tsv is updated deliberately from cost-matrix-stock.tsv.
COST_VARIANTS = ("default", "prefer_natural_surfaces=0", "prefer_gravel_surfaces=0", "prefer_singletrails=1",
                 "consider_traffic_estimate=0", "allow_steps=1", "allow_ferries=0", "consider_noise=1",
                 "consider_forest=1")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Clean BRouter v1.7.8 Git checkout")
    parser.add_argument("--java-home", type=Path, help="JDK 17 directory; otherwise use PATH")
    parser.add_argument("--deps", required=True, type=Path)
    parser.add_argument("--fetch-deps", action="store_true", help="Download missing pinned test jars")
    parser.add_argument("--profile", type=Path, default=ROOT / "profiles/gravel-max.brf")
    parser.add_argument("--out", type=Path, default=ROOT / "output/current/profile-v1-validation")
    args = parser.parse_args()
    source = args.source.resolve()
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if revision != REVISION:
        parser.error(f"Expected BRouter v1.7.8 commit {REVISION}; got {revision}")
    dirty = subprocess.check_output(["git", "-C", str(source), "status", "--porcelain", "--untracked-files=no"], text=True)
    if dirty:
        parser.error("BRouter tracked source has local modifications")
    java = str(args.java_home / "bin/java") if args.java_home else shutil.which("java")
    javac = str(args.java_home / "bin/javac") if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("A JDK is required; supply --java-home")
    args.deps.mkdir(parents=True, exist_ok=True)
    pinned = {name: ("https://repo.maven.apache.org/maven2/" + prefix + name, digest)
              for name, (prefix, digest) in DEPENDENCIES.items()}
    pinned.update(LOOKUPS)
    for name, (url, digest) in pinned.items():
        path = args.deps / name
        if not path.exists() and args.fetch_deps:
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError(f"Downloaded dependency checksum mismatch: {name}")
            path.write_bytes(data)
        if not path.exists() or sha(path) != digest:
            parser.error(f"Missing or invalid dependency: {path}; use --fetch-deps to fetch missing files")
    jars = [str((args.deps / name).resolve()) for name in DEPENDENCIES]
    v11_lookups = (args.deps / "lookups-v1.7.10.dat").resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    # A failed run must not leave a previous success report at the requested path.
    report_path = out / "validation.json"
    report_path.unlink(missing_ok=True)
    profile = args.profile.resolve()
    report = {"brouter_revision": revision, "profile_sha256": sha(profile),
              "java": subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True).strip(),
              "dependency_sha256": {name: digest for name, (_, digest) in DEPENDENCIES.items()},
              "checks": [], "field_validated": False}
    archetypes = ROOT / "tests/brouter/archetypes.txt"
    expected_costs = ROOT / "tests/brouter/expected-costs.tsv"
    verified_sources = [ROOT / "scripts/test_brouter_profile.py", *sorted((ROOT / "tests/brouter").glob("*.java")),
                        archetypes, expected_costs]
    report["test_source_sha256"] = {str(path.relative_to(ROOT)): sha(path) for path in verified_sources}
    modules = ["util", "codec", "expressions", "mapaccess", "core", "map-creator"]
    with tempfile.TemporaryDirectory(prefix="gravel-max-brouter-test-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        sources = []
        for module in modules:
            sources.extend(sorted((source / f"brouter-{module}/src/main/java").rglob("*.java")))
        sources.extend(sorted((ROOT / "tests/brouter").glob("*.java")))
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", os.pathsep.join(jars), "-d", str(classes),
                        *map(str, sources)], check=True, timeout=180)
        cp = os.pathsep.join([str(classes), *jars])
        stock = source / "misc/profiles2/lookups.dat"
        commands = [("stock-contracts", "ProfileContractTest", [str(profile), str(stock)]),
                    ("contracts-lookups-v11", "ProfileContractTest", [str(profile), str(v11_lookups)]),
                    # RD5 segment encoding is bound to the map creator's lookups, so routing stays on 1.7.8.
                    ("routing-fixture", "RouteFixtureTest", [str(source), str(profile), str(work / "fixture")])]
        def run(name, klass, arguments):
            print(f"Running {name}...", flush=True)
            result = subprocess.run([java, "-ea", "-Xmx512m", "-cp", cp, klass, *arguments],
                                    capture_output=True, text=True, timeout=180)
            (out / f"{name}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
            if result.returncode:
                print(result.stdout[-6000:] + result.stderr[-6000:])
                raise SystemExit(result.returncode)
            return result.stdout

        for name, klass, arguments in commands:
            summary = run(name, klass, arguments).strip().splitlines()[-1]
            report["checks"].append({"name": name, "status": "passed", "summary": summary})
            print(summary, flush=True)
        # Per-archetype costs on both lookup tables: tuning evidence, and a guard that
        # the profile evaluates identically wherever it parses.
        tables, matrices = {}, {}
        for suffix, lookups in (("stock", stock), ("v11", v11_lookups)):
            tables[suffix] = run(f"cost-table-{suffix}", "CostTable", [str(profile), str(lookups), str(archetypes)])
            (out / f"cost-table-{suffix}.tsv").write_text(tables[suffix], encoding="utf-8")
            matrices[suffix] = run(f"cost-matrix-{suffix}", "CostTable",
                                   [str(profile), str(lookups), str(archetypes), *COST_VARIANTS])
            (out / f"cost-matrix-{suffix}.tsv").write_text(matrices[suffix], encoding="utf-8")
        if tables["stock"] != tables["v11"] or matrices["stock"] != matrices["v11"]:
            print("Cost tables differ between lookup tables; compare cost-table-*.tsv and cost-matrix-*.tsv")
            raise SystemExit(1)
        expected = expected_costs.read_text(encoding="utf-8")
        if matrices["stock"] != expected:
            diff = list(difflib.unified_diff(expected.splitlines(), matrices["stock"].splitlines(),
                                             "tests/brouter/expected-costs.tsv", "cost-matrix-stock.tsv", lineterm="", n=0))
            print("\n".join(diff[:60]))
            print(f"Costs differ from tests/brouter/expected-costs.tsv ({len(diff)} diff lines). If the change is "
                  f"intended, review it and copy {out / 'cost-matrix-stock.tsv'} over the expected file.")
            raise SystemExit(1)
        summary = (f"CostTable: {len(tables['stock'].splitlines())} archetypes, identical on both lookups; "
                   f"{len(expected.splitlines())} rows over {len(COST_VARIANTS)} option sets match expected-costs.tsv")
        report["checks"].append({"name": "cost-table", "status": "passed", "summary": summary})
        print(summary, flush=True)
        report["lookups_sha256"] = {"stock": sha(stock), "v1.7.10": sha(v11_lookups)}
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Validation report: {report_path}")


if __name__ == "__main__":
    main()
