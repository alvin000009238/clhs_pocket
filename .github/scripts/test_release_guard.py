"""Local-only release checks; tags exist only in a temporary Git repository."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from release_guard import check_upgrade, provenance, verify, version


class ReleaseGuardTest(unittest.TestCase):
    def test_release_workflow_preserves_pending_tags(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows" / "android-release.yml").read_text()
        self.assertIn("queue: max", workflow)

    def test_verify_checks_metadata_and_abi_without_signature_check(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "release.apk"
            with ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libapp.so", b"native")
            with (
                patch("release_guard.metadata", return_value=(35, "2.1.0")),
                patch("release_guard.run") as run,
            ):
                verify(apk, "v2.1.0", 35)
                run.assert_not_called()

    def test_release_gates(self):
        original = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            os.chdir(directory)
            try:
                def git(*args):
                    return subprocess.check_output(["git", *args], stderr=subprocess.STDOUT, text=True).strip()

                git("init", "-b", "main")
                git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "main")
                git("update-ref", "refs/remotes/origin/main", "HEAD")
                git("tag", "v2.0.1")
                notes = "## [2.0.1] - 2026-09-18\n\nRelease notes\n\n## [2.0.0]\nOld\n"
                self.assertEqual(provenance("v2.0.1", notes), "Release notes\n")
                with self.assertRaises(ValueError):
                    provenance("v2.0.1", "## [2.0.0]\nOld")
                with self.assertRaises(ValueError):
                    provenance("v2.0.1", "## [2.0.1]\n\n")
                git("checkout", "-b", "dev")
                git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "unmerged")
                git("tag", "v2.0.2")
                with self.assertRaises(subprocess.CalledProcessError):
                    provenance("v2.0.2", "## [2.0.2]\nUnmerged")
                # An older main ancestor remains eligible; HEAD must still match its tag.
                git("update-ref", "refs/remotes/origin/main", "HEAD")
                git("checkout", "v2.0.1")
                self.assertEqual(provenance("v2.0.1", notes), "Release notes\n")
            finally:
                os.chdir(original)
        for invalid in ("v2", "v2.0.1-rc1", "v02.0.1", "2.0.1", "v2.0.1\n"):
            with self.subTest(tag=invalid), self.assertRaises(ValueError):
                version(invalid)
        check_upgrade(35, [10, 34, 20])
        for code, previous in ((34, [34]), (33, [34]), (1, []), (2100000001, [34])):
            with self.subTest(code=code), self.assertRaises(ValueError):
                check_upgrade(code, previous)


if __name__ == "__main__":
    unittest.main()
