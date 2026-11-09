"""Verify reviewed policy installation and tamper checks in temporary folders without accessing application databases."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("seed_policy_fixture", PROJECT / "scripts/seed-presenter-a2a.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class PolicyFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundled = self.root / "app/data/policies"
        self.bundled.mkdir(parents=True)
        self.folder = self.root / "policy-documents"
        self.folder.mkdir()
        for name in fixture.POLICY_TEXT_HASHES:
            content = (PROJECT / "app/data/policies" / name).read_bytes()
            self.assertEqual(content, (PROJECT / "policy-documents" / name).read_bytes())
            (self.bundled / name).write_bytes(content)

    def test_reviewed_pack_installs_and_reuses_exact_versions(self):
        first = fixture.install_policies(self.root, self.folder)
        self.assertEqual(first, fixture.install_policies(self.root, self.folder))
        self.assertEqual(len(first), 2)
        for name, expected in first.items():
            self.assertEqual(hashlib.sha256((self.folder / name).read_bytes()).hexdigest(), expected)

    def test_matching_active_files_do_not_create_duplicate_presenter_documents(self):
        for name in fixture.POLICY_TEXT_HASHES:
            (self.folder / name).write_bytes((self.bundled / name).read_bytes())
        installed = fixture.install_policies(self.root, self.folder)
        self.assertEqual(set(installed), set(fixture.POLICY_TEXT_HASHES))
        self.assertFalse((self.folder / "presenter").exists())

    def test_line_endings_are_allowed_but_changed_content_is_rejected(self):
        for name in fixture.POLICY_TEXT_HASHES:
            path = self.bundled / name
            path.write_bytes(path.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
        self.assertEqual(len(fixture.install_policies(self.root, self.folder)), 2)
        path = self.bundled / next(iter(fixture.POLICY_TEXT_HASHES))
        path.write_bytes(path.read_bytes() + b"\r\nunreviewed change")
        with self.assertRaisesRegex(RuntimeError, "differs from the reviewed demo fixture"):
            fixture.install_policies(self.root, self.folder)


if __name__ == "__main__":
    unittest.main()
