"""Local-only release checks; tags exist only in a temporary Git repository."""

import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from unittest.mock import patch
from zipfile import ZipFile

from release_guard import (
    NoRedirect, check_upgrade, dev_provenance, firebase_app, firebase_history,
    github_history, provenance, release_target, verify, version,
)


APP_ID = "1:1021793264456:android:d63f8d7b98f264b124497d"
SHA = "a" * 40


class ReleaseGuardTest(unittest.TestCase):
    def test_firebase_config_rejects_invalid_group_before_writing_environment(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            for index, group in enumerate(("", "abcd", "a" * 63, "abc", "a" * 64, "dev testers", "dev\ntesters")):
                with self.subTest(group=group):
                    output = Path(directory) / f"environment-{index}"
                    env = dict(os.environ, GITHUB_REF="refs/heads/dev", GITHUB_EVENT_NAME="push",
                               GITHUB_ENV=str(output), CONFIGURED_FIREBASE_APP_ID="",
                               CONFIGURED_FIREBASE_GROUP=group)
                    result = subprocess.run([sys.executable, str(root / ".github/scripts/release_guard.py"),
                                             "firebase-config"], cwd=root, env=env,
                                            capture_output=True, text=True, encoding="utf-8")
                    if group in ("", "abcd", "a" * 63):
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self.assertIn(f"FIREBASE_GROUP={group or 'dev-testers'}\n", output.read_text())
                    else:
                        self.assertNotEqual(result.returncode, 0)
                        self.assertFalse(output.exists())

    def test_release_workflow_preserves_pending_tags(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows" / "android-release.yml").read_text()
        self.assertIn("queue: max", workflow)
        self.assertIn("group: android-release\n  cancel-in-progress: false", workflow)
        self.assertIn("branches:\n      - dev\n    tags:\n      - 'v*'", workflow)
        self.assertEqual(workflow.count("concurrency:"), 1)
        self.assertLess(workflow.index(":app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --tests 'com.clhs.score.FlavorConfigurationTest' :app:lintDemoDebug :app:assembleDemoDebug :app:assembleProductionDebug --warning-mode all"), workflow.index("- name: Decode release keystore"))

    def test_verify_checks_metadata_and_abi_without_signature_check(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "release.apk"
            with ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libapp.so", b"native")
            with (
                patch("release_guard.metadata", return_value=(35, "2.1.0")),
                patch("release_guard.run") as run,
            ):
                verify(apk, "2.1.0", 35)
                run.assert_not_called()
                for name, code in (("2.1.0-dev.35.aaaaaaa", 35), ("2.1.0", 36)):
                    with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                        verify(apk, name, code)
            with patch("release_guard.metadata", return_value=(35, "2.1.0-dev.35.aaaaaaa")):
                verify(apk, "2.1.0-dev.35.aaaaaaa", 35)
            with ZipFile(apk, "a") as archive:
                archive.writestr("lib/x86_64/libapp.so", b"native")
            with patch("release_guard.metadata", return_value=(35, "2.1.0")), self.assertRaises(ValueError):
                verify(apk, "2.1.0", 35)

    def test_targets_use_exact_shared_run_number_and_latest_stable(self):
        stable = release_target("push", "refs/tags/v2.1.0", "35", SHA, "v2.0.9")
        self.assertEqual((stable.channel, stable.version_name, stable.version_code), ("stable", "2.1.0", 35))
        dev = release_target("push", "refs/heads/dev", "36", SHA, "v2.0.9")
        self.assertEqual((dev.channel, dev.version_name, dev.version_code), ("dev", "2.0.9-dev.36.aaaaaaa", 36))
        for event, ref, code, sha, latest in (
            ("pull_request", "refs/heads/dev", "36", SHA, "v2.0.9"),
            ("push", "refs/heads/main", "36", SHA, "v2.0.9"),
            ("push", "refs/tags/v2.0.9", "36", SHA, "v2.0.9"),
            ("push", "refs/tags/v2.0.8", "36", SHA, "v2.0.9"),
            ("push", "refs/heads/dev", "2100000001", SHA, "v2.0.9"),
            ("push", "refs/heads/dev", "36", "short", "v2.0.9"),
            ("push", "refs/heads/dev", "36", SHA, ""),
        ):
            with self.subTest(ref=ref, code=code), self.assertRaises(ValueError):
                release_target(event, ref, code, sha, latest)

    def test_firebase_app_matches_production_package_and_optional_variable(self):
        config = json.loads((Path(__file__).resolve().parents[2] / "android/app/google-services.json").read_text())
        self.assertEqual(firebase_app(config), APP_ID)
        self.assertEqual(firebase_app(config, APP_ID), APP_ID)
        with self.assertRaises(ValueError):
            firebase_app(config, "1:1021793264456:android:wrong")
        config["project_info"]["project_number"] = "2"
        with self.assertRaises(ValueError):
            firebase_app(config)

    def test_firebase_history_reads_all_pages_with_bearer_and_fixed_host(self):
        pages = [{"releases": [{"buildVersion": "34"}], "nextPageToken": "next/ +"},
                 {"releases": [{"buildVersion": "38"}]}]
        with patch("release_guard.urllib.request.build_opener") as factory:
            opener = factory.return_value
            opener.open.side_effect = [io.BytesIO(json.dumps(page).encode()) for page in pages]
            self.assertEqual(firebase_history(APP_ID, "local-test-token"), [34, 38])
            self.assertIsInstance(factory.call_args.args[0], NoRedirect)
            requests = [call.args[0] for call in opener.open.call_args_list]
            for request in requests:
                url = urllib.parse.urlparse(request.full_url)
                self.assertEqual(url.scheme, "https")
                self.assertEqual(url.netloc, "firebaseappdistribution.googleapis.com")
                self.assertEqual(url.path, f"/v1/projects/1021793264456/apps/{APP_ID}/releases")
                self.assertEqual(request.get_header("Authorization"), "Bearer local-test-token")
            self.assertEqual(urllib.parse.parse_qs(urllib.parse.urlparse(requests[1].full_url).query)["pageToken"], ["next/ +"])
        check_upgrade(39, [34, 38])
        for code in (37, 38):
            with self.subTest(code=code), self.assertRaises(ValueError):
                check_upgrade(code, [34, 38])

    def test_firebase_empty_bootstrap_and_fail_closed_errors(self):
        for page in ({}, {"releases": []}):
            with patch("release_guard.urllib.request.build_opener") as factory:
                factory.return_value.open.return_value = io.BytesIO(json.dumps(page).encode())
                self.assertEqual(firebase_history(APP_ID, "token"), [])
        for page in ([], {"releases": None}, {"releases": [None]}, {"releases": [{}]},
                     {"releases": [{"buildVersion": 34}]}, {"nextPageToken": 3},
                     *({"releases": [{"buildVersion": value}]} for value in ("0", "-1", "34.0", "2100000001"))):
            with self.subTest(page=page), patch("release_guard.urllib.request.build_opener") as factory:
                factory.return_value.open.return_value = io.BytesIO(json.dumps(page).encode())
                with self.assertRaises(ValueError):
                    firebase_history(APP_ID, "token")
        with patch("release_guard.urllib.request.build_opener") as factory:
            factory.return_value.open.side_effect = [io.BytesIO(b'{"nextPageToken":"same"}'), io.BytesIO(b'{"nextPageToken":"same"}')]
            with self.assertRaises(ValueError):
                firebase_history(APP_ID, "token")
        for error in (urllib.error.HTTPError("https://firebaseappdistribution.googleapis.com", 401, "Unauthorized", {}, None), urllib.error.URLError("offline")):
            with self.subTest(error=error), patch("release_guard.urllib.request.build_opener") as factory:
                factory.return_value.open.side_effect = error
                with self.assertRaises(urllib.error.URLError):
                    firebase_history(APP_ID, "token")
        with self.assertRaises(ValueError):
            NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://example.invalid")
        for app, token in ((APP_ID, ""), ("https://example.invalid", "token")):
            with self.subTest(app=app), self.assertRaises(ValueError):
                firebase_history(app, token)

    def test_github_history_verifies_all_apks_and_stable_versions(self):
        asset = {"name": "app.apk", "browser_download_url": "https://github.com/owner/repo/releases/download/v2.0.0/app.apk", "digest": "sha256:" + hashlib.sha256(b"apk").hexdigest()}
        stable = {"tag_name": "v2.0.0", "draft": False, "prerelease": False, "assets": [asset]}
        newer = dict(stable, tag_name="v2.1.0")
        pre = dict(stable, tag_name="preview", prerelease=True)
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), patch("release_guard.run", return_value=json.dumps([[stable], [newer, pre]])) as command, patch("release_guard.urllib.request.urlopen", side_effect=lambda *args, **kwargs: io.BytesIO(b"apk")), patch("release_guard.metadata", side_effect=[(34, "2.0.0"), (36, "2.1.0"), (37, "preview")]):
            self.assertEqual(github_history("refs/heads/dev"), ([34, 36, 37], "v2.1.0"))
            self.assertIn("--paginate", command.call_args.args)
            self.assertIn("--slurp", command.call_args.args)
        for releases in ([], [[]], {}, [[dict(stable, assets=[])]], [[dict(stable, assets=[dict(asset, digest="sha256:wrong")])]], [[dict(stable, tag_name="invalid")]], [[dict(stable, draft="false")]]):
            with self.subTest(releases=releases), patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), patch("release_guard.run", return_value=json.dumps(releases)), patch("release_guard.urllib.request.urlopen", side_effect=lambda *args, **kwargs: io.BytesIO(b"apk")), self.assertRaises(ValueError):
                github_history("refs/heads/dev")
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), patch("release_guard.run", return_value=json.dumps([[stable]])), self.assertRaises(ValueError):
            github_history("refs/tags/v2.0.0")

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
                git("update-ref", "refs/remotes/origin/dev", "HEAD")
                dev_provenance(git("rev-parse", "HEAD"))
                with self.assertRaises(ValueError):
                    dev_provenance("b" * 40)
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
                    dev_provenance(git("rev-parse", "HEAD"))
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
