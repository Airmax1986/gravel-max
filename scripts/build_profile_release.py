#!/usr/bin/env python3
"""Build a reproducible code-only release after matching validation and license."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_CHECKS = ("stock-contracts", "contracts-lookups-v11", "routing-fixture", "cost-table")
FILES = {
    "PROFILE_README.md": "README.md",
    "PROFILE_VERSION": "PROFILE_VERSION",
    "PROFILE_CHANGELOG.md": "PROFILE_CHANGELOG.md",
    "LICENSE": "LICENSE",
    "THIRD_PARTY_NOTICES.md": "THIRD_PARTY_NOTICES.md",
    "profiles/gravel-max.brf": "profiles/gravel-max.brf",
    "docs/GRAVEL_MAX_PROFILE.md": "docs/GRAVEL_MAX_PROFILE.md",
    "docs/BIKEROUTER_PROFILE_INTEGRATION.md": "docs/BIKEROUTER_PROFILE_INTEGRATION.md",
    "scripts/test_brouter_profile.py": "scripts/test_brouter_profile.py",
    "scripts/build_profile_release.py": "scripts/build_profile_release.py",
    "test_profile_release.py": "test_profile_release.py",
    "tests/brouter/ProfileContractTest.java": "tests/brouter/ProfileContractTest.java",
    "tests/brouter/RouteFixtureTest.java": "tests/brouter/RouteFixtureTest.java",
    "tests/brouter/CostTable.java": "tests/brouter/CostTable.java",
    "tests/brouter/archetypes.txt": "tests/brouter/archetypes.txt",
    "tests/brouter/expected-costs.tsv": "tests/brouter/expected-costs.tsv",
    "scripts/check_lookups.py": "scripts/check_lookups.py",
    ".github/workflows/profile.yml": ".github/workflows/profile.yml",
    ".gitignore": ".gitignore",
    "scripts/check_release_tree.py": "scripts/check_release_tree.py",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contents(root, validation, candidate=False):
    version = (root / "PROFILE_VERSION").read_text().strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("PROFILE_VERSION must be a semantic release version")
    if not candidate and not (root / "LICENSE").is_file():
        raise ValueError("Owner-selected LICENSE is required before creating a publishable release")
    headers = {
        "profiles/gravel-max.brf": f"# gravel-max routing profile v{version}",
        "docs/GRAVEL_MAX_PROFILE.md": f"# gravel-max BRouter profile v{version}",
    }
    for relative_path, header in headers.items():
        lines = (root / relative_path).read_text().splitlines()
        if not lines or lines[0] != header:
            raise ValueError(f"Version header mismatch: {relative_path}")
    report = json.loads(validation.read_text())
    if report.get("profile_sha256") != digest((root / "profiles/gravel-max.brf").read_bytes()):
        raise ValueError("Validation does not match the current profile; rerun tests")
    checks = {entry["name"]: entry["status"] for entry in report.get("checks", [])}
    for name in REQUIRED_CHECKS:
        if checks.get(name) != "passed":
            raise ValueError(f"Required validation check missing or failed: {name}")
    required_test_files = [key for key in FILES if key.startswith("tests/") or key == "scripts/test_brouter_profile.py"]
    for name in required_test_files:
        if report.get("test_source_sha256", {}).get(name) != digest((root / name).read_bytes()):
            raise ValueError(f"Validation does not match current test source: {name}")
    files = {}
    for source, target in FILES.items():
        if candidate and source == "LICENSE":
            continue
        path = root / source
        if source == "PROFILE_README.md" and not path.exists():
            path = root / "README.md"  # source release maps its README to the standard name
        files[target] = path.read_bytes()
    if candidate:
        files["PUBLICATION_PENDING.txt"] = (
            "Local review candidate, not a published or licensed release.\n"
            "The project owner has not yet selected the code license or confirmed\n"
            "the publication destination. No open-source license is granted by\n"
            "this archive. Personal GPX and bundled geodata are excluded.\n"
        ).encode()
    files["validation/validation.json"] = validation.read_bytes()
    files["SHA256SUMS"] = "".join(f"{digest(data)}  {name}\n" for name, data in sorted(files.items())).encode()
    return files


def build(root, validation, destination, candidate=False):
    version = (root / "PROFILE_VERSION").read_text().strip()
    contents_ = contents(root, validation, candidate)
    destination.mkdir(parents=True, exist_ok=True)
    label = f"gravel-max-{version}" + ("-unlicensed-candidate" if candidate else "")
    archive = destination / f"{label}.zip"
    # Fixed timestamps/order/permissions make identical validated inputs reproducible.
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(contents_.items()):
            entry = zipfile.ZipInfo(f"{label}/{name}", date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            z.writestr(entry, data)
    checksum = digest(archive.read_bytes())
    archive.with_suffix(".zip.sha256").write_text(f"{checksum}  {archive.name}\n")
    return archive


def write_tree(root, validation, directory, candidate=False):
    written = []
    for name, data in sorted(contents(root, validation, candidate).items()):
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        written.append(name)
    return sorted(written)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validation", type=Path, default=ROOT / "output/current/profile-v1-validation/validation.json")
    parser.add_argument("--out", type=Path, default=ROOT / "output/current/releases")
    parser.add_argument("--candidate", action="store_true", help="Create an explicitly unlicensed local review artifact")
    parser.add_argument("--tree", type=Path, help="Also write the release contents as a plain directory tree")
    args = parser.parse_args()
    print(build(ROOT, args.validation, args.out, candidate=args.candidate))
    if args.tree:
        written = write_tree(ROOT, args.validation, args.tree, candidate=args.candidate)
        print(f"Wrote {len(written)} files to {args.tree}:")
        for name in written:
            print(f"  {name}")


if __name__ == "__main__":
    main()
