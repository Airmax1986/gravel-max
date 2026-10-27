"""Release integrity: data exclusion, fresh evidence and deterministic content."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from scripts.build_profile_release import FILES, REQUIRED_CHECKS, build, contents, digest, write_tree
from scripts.check_release_tree import EvidenceMismatch, check

ROOT = Path(__file__).resolve().parent


class ProfileReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in FILES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture " + name)
        (self.root / "profiles/gravel-max.brf").write_text("# gravel-max routing profile v1.0.0\nfixture")
        (self.root / "docs/GRAVEL_MAX_PROFILE.md").write_text("# gravel-max BRouter profile v1.0.0\nfixture")
        (self.root / "PROFILE_VERSION").write_text("1.0.0\n")
        (self.root / "personal.gpx").write_text("private coordinates")
        report = {
            "profile_sha256": digest((self.root / "profiles/gravel-max.brf").read_bytes()),
            "checks": [{"name": name, "status": "passed"} for name in REQUIRED_CHECKS],
            "test_source_sha256": {name: digest((self.root / name).read_bytes()) for name in FILES
                                   if name.startswith("tests/") or name == "scripts/test_brouter_profile.py"},
        }
        self.validation = self.root / "validation.json"
        self.validation.write_text(json.dumps(report))

    def test_requires_owner_license(self):
        (self.root / "LICENSE").unlink()
        with self.assertRaisesRegex(ValueError, "LICENSE"):
            build(self.root, self.validation, self.root / "out")

    def test_rejects_stale_profile_and_tests(self):
        for name in ["profiles/gravel-max.brf", "tests/brouter/ProfileContractTest.java"]:
            path = self.root / name
            original = path.read_bytes()
            path.write_bytes(original.split(b"\n", 1)[0] + b"\nchanged")
            with self.assertRaisesRegex(ValueError, "Validation does not match"):
                build(self.root, self.validation, self.root / "out")
            path.write_bytes(original)

    def test_candidate_cannot_be_mistaken_for_licensed_release(self):
        (self.root / "LICENSE").unlink()
        archive = build(self.root, self.validation, self.root / "out", candidate=True)
        self.assertIn("unlicensed-candidate", archive.name)
        with zipfile.ZipFile(archive) as z:
            self.assertTrue(any(n.endswith("PUBLICATION_PENDING.txt") for n in z.namelist()))
            self.assertFalse(any(n.endswith("/LICENSE") for n in z.namelist()))

    def test_rejects_missing_required_check(self):
        original = json.loads(self.validation.read_text())
        for name in REQUIRED_CHECKS:
            with self.subTest(check=name):
                report = dict(original, checks=[c for c in original["checks"] if c["name"] != name])
                self.validation.write_text(json.dumps(report))
                with self.assertRaisesRegex(ValueError, name):
                    build(self.root, self.validation, self.root / "out")

    def test_rejects_version_header_mismatch(self):
        (self.root / "profiles/gravel-max.brf").write_text("# gravel-max routing profile v9.9.9\nfixture")
        report = json.loads(self.validation.read_text())
        report["profile_sha256"] = digest((self.root / "profiles/gravel-max.brf").read_bytes())
        self.validation.write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "Version header mismatch"):
            build(self.root, self.validation, self.root / "out")

    def make_tree(self):
        tree = self.root / "tree"
        write_tree(self.root, self.validation, tree)
        return tree

    def test_tree_matches_zip_and_passes_check(self):
        tree = self.make_tree()
        archive = build(self.root, self.validation, self.root / "out")
        with zipfile.ZipFile(archive) as z:
            for name, data in contents(self.root, self.validation).items():
                self.assertEqual((tree / name).read_bytes(), z.read("gravel-max-1.0.0/" + name))
        names = write_tree(self.root, self.validation, self.root / "again")
        self.assertEqual(names, sorted(set(FILES.values()) | {"SHA256SUMS", "validation/validation.json"}))
        self.assertIn("OK", check(tree))

    def test_tree_detects_modified_profile(self):
        tree = self.make_tree()
        with (tree / "profiles/gravel-max.brf").open("ab") as f:
            f.write(b"x")
        with self.assertRaisesRegex(EvidenceMismatch, "profiles/gravel-max.brf"):
            check(tree)

    def test_tree_detects_altered_sums_hash(self):
        tree = self.make_tree()
        lines = (tree / "SHA256SUMS").read_text().splitlines()
        sha, name = lines[0].split("  ", 1)
        lines[0] = ("0" if sha[0] != "0" else "1") + sha[1:] + "  " + name
        (tree / "SHA256SUMS").write_text("\n".join(lines) + "\n")
        with self.assertRaisesRegex(EvidenceMismatch, name):
            check(tree)

    def test_tree_detects_unlisted_file(self):
        tree = self.make_tree()
        (tree / "extra.txt").write_text("unlisted")
        with self.assertRaisesRegex(EvidenceMismatch, "extra.txt"):
            check(tree)

    def test_tree_ignores_output_and_pycache(self):
        tree = self.make_tree()
        (tree / "output").mkdir()
        (tree / "output/result.txt").write_text("ignored")
        (tree / "scripts/__pycache__").mkdir()
        (tree / "scripts/__pycache__/x.pyc").write_bytes(b"ignored")
        self.assertIn("OK", check(tree))

    def test_fresh_validation_comparison(self):
        tree = self.make_tree()
        committed = json.loads((tree / "validation/validation.json").read_text())
        fresh = self.root / "fresh.json"
        fresh.write_text(json.dumps(dict(committed, java="other jvm")))
        self.assertIn("OK", check(tree, fresh))
        fresh.write_text(json.dumps(dict(committed, profile_sha256="0" * 64)))
        with self.assertRaisesRegex(EvidenceMismatch, "profile_sha256"):
            check(tree, fresh)

    def test_non_release_tree_exits_2(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/check_release_tree.py"), "--root", str(self.root / "nothing")],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("not a release tree", result.stderr)

    def test_reproducible_allowlist_and_checksums(self):
        first = build(self.root, self.validation, self.root / "one")
        second = build(self.root, self.validation, self.root / "two")
        self.assertEqual(first.read_bytes(), second.read_bytes())
        prefix = "gravel-max-1.0.0/"
        with zipfile.ZipFile(first) as archive:
            expected = {prefix + value for value in FILES.values()} | {
                prefix + "SHA256SUMS", prefix + "validation/validation.json"}
            self.assertEqual(set(archive.namelist()), expected)
            self.assertNotIn("personal.gpx", str(archive.namelist()))
            for line in archive.read(prefix + "SHA256SUMS").decode().splitlines():
                sha, name = line.split("  ", 1)
                self.assertEqual(hashlib.sha256(archive.read(prefix + name)).hexdigest(), sha)
            archive.extractall(self.root / "extracted")
        extracted = self.root / "extracted" / "gravel-max-1.0.0"
        rebuilt = build(extracted, extracted / "validation/validation.json", self.root / "three")
        self.assertEqual(first.read_bytes(), rebuilt.read_bytes())


if __name__ == "__main__":
    unittest.main()
