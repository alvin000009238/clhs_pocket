"""Fail-closed release checks. Uses only Python, Git, gh and Android build tools."""

import hashlib
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
import urllib.parse
from zipfile import ZipFile


def run(*args):
    return subprocess.check_output(args, text=True, encoding="utf-8").strip()


def version(tag):
    if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag):
        raise ValueError("Expected a stable vMAJOR.MINOR.PATCH tag")
    return tuple(map(int, tag[1:].split(".")))


def version_code(value):
    if not re.fullmatch(r"[1-9][0-9]*", str(value)) or int(value) > 2100000000:
        raise ValueError("versionCode must be a positive integer <= 2100000000")
    return int(value)


@dataclass(frozen=True)
class ReleaseTarget:
    channel: str
    version_name: str
    version_code: int


def release_target(event, ref, code, sha, latest_stable):
    code = version_code(code)
    if event != "push" or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected a push with a full commit SHA")
    if ref == "refs/heads/dev":
        version(latest_stable)
        return ReleaseTarget("dev", f"{latest_stable[1:]}-dev.{code}.{sha[:7]}", code)
    if ref.startswith("refs/tags/"):
        tag = ref.removeprefix("refs/tags/")
        version(tag)
        if version(tag) <= version(latest_stable):
            raise ValueError("Release version must exceed all published stable versions")
        return ReleaseTarget("stable", tag[1:], code)
    raise ValueError("Expected dev branch or a stable version tag")


def dev_provenance(sha):
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or run("git", "rev-parse", "HEAD") != sha:
        raise ValueError("Checkout does not match triggering dev commit")
    subprocess.run(["git", "merge-base", "--is-ancestor", sha, "origin/dev"], check=True)


def provenance(tag, changelog):
    version(tag)
    commit = run("git", "rev-parse", f"refs/tags/{tag}^{{commit}}")
    if commit != run("git", "rev-parse", "HEAD"):
        raise ValueError("Checkout does not match release tag")
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, "origin/main"], check=True)
    section = re.search(
        rf"^## \[{re.escape(tag[1:])}\](?:[ \t]+-[ \t]+[^\n]*)?[ \t]*\r?\n(.*?)(?=^## \[|\Z)",
        changelog, re.MULTILINE | re.DOTALL,
    )
    if not section or not section[1].strip():
        raise ValueError("Missing or empty CHANGELOG section")
    return section[1].strip() + "\n"


def check_upgrade(code, previous):
    version_code(code)
    if not previous or not max(version_code(value) for value in previous) < code:
        raise ValueError("versionCode must exceed every published APK (and remain <= 2100000000)")


def metadata(apk):
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / os.environ.get("BUILD_TOOLS_VERSION", "37.0.0")
    suffix = ".exe" if os.name == "nt" else ""
    badging = run(str(tools / f"aapt{suffix}"), "dump", "badging", str(apk))
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    if not match or match[1] != "com.clhs.score":
        raise ValueError("Unexpected APK applicationId or unreadable metadata")
    return int(match[2]), match[3]


def github_history(ref):
    # Inspect all published APKs, including prereleases; never infer codes from tag names.
    pages = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{os.environ['GITHUB_REPOSITORY']}/releases?per_page=100"))
    if not isinstance(pages, list) or not pages or any(not isinstance(page, list) for page in pages):
        raise ValueError("Malformed GitHub release history")
    previous = []
    stable_tags = []
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "previous.apk"
        for release in (release for page in pages for release in page):
            if not isinstance(release, dict) or type(release.get("draft")) is not bool or type(release.get("prerelease")) is not bool or not isinstance(release.get("tag_name"), str) or not isinstance(release.get("assets"), list):
                raise ValueError("Malformed GitHub release")
            if release["draft"]:
                continue
            if ref == f"refs/tags/{release['tag_name']}":
                raise ValueError("Published releases are immutable; use a new version tag")
            if not release["prerelease"]:
                version(release["tag_name"])
                stable_tags.append(release["tag_name"])
            if any(not isinstance(asset, dict) or not isinstance(asset.get("name"), str) for asset in release["assets"]):
                raise ValueError("Malformed GitHub release asset")
            assets = [asset for asset in release["assets"] if asset["name"].endswith(".apk")]
            if not assets:
                raise ValueError("Published release has no APK; cannot establish versionCode history")
            for asset in assets:
                url = asset.get("browser_download_url")
                if not isinstance(url, str) or not url.startswith(f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/download/"):
                    raise ValueError("Unexpected release asset URL")
                with urllib.request.urlopen(url, timeout=120) as response:
                    data = response.read()
                if asset.get("digest") != "sha256:" + hashlib.sha256(data).hexdigest():
                    raise ValueError("Missing or mismatched release asset SHA-256")
                apk.write_bytes(data)
                old_code, old_name = metadata(apk)
                print(f"Published {release['tag_name']}: versionCode={old_code}, versionName={old_name}")
                previous.append(version_code(old_code))
    if not previous or not stable_tags:
        raise ValueError("GitHub APK history and a published stable version are required")
    return previous, max(stable_tags, key=version)


def firebase_app(config, configured_id=""):
    clients = [client["client_info"]["mobilesdk_app_id"] for client in config["client"]
               if client["client_info"]["android_client_info"]["package_name"] == "com.clhs.score"]
    if len(clients) != 1 or not re.fullmatch(r"1:[0-9]+:android:[0-9a-f]+", clients[0]):
        raise ValueError("Expected exactly one production Firebase Android app")
    app_id = clients[0]
    if app_id.split(":")[1] != str(config["project_info"]["project_number"]) or configured_id and configured_id != app_id:
        raise ValueError("Firebase app ID does not match production google-services.json")
    return app_id


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Firebase history redirects are forbidden")


def firebase_history(app_id, token):
    if not re.fullmatch(r"1:[0-9]+:android:[0-9a-f]+", app_id) or not token:
        raise ValueError("Firebase production app ID and access token are required")
    base = f"https://firebaseappdistribution.googleapis.com/v1/projects/{app_id.split(':')[1]}/apps/{app_id}/releases"
    opener = urllib.request.build_opener(NoRedirect())
    codes, seen_tokens = [], set()
    page_token = ""
    while True:
        query = urllib.parse.urlencode({"pageSize": 100, "pageToken": page_token})
        request = urllib.request.Request(f"{base}?{query}", headers={"Authorization": f"Bearer {token}"})
        with opener.open(request, timeout=120) as response:
            page = json.load(response)
        if not isinstance(page, dict) or not isinstance(page.get("releases", []), list):
            raise ValueError("Malformed Firebase release history")
        for release in page.get("releases", []):
            if not isinstance(release, dict) or not isinstance(release.get("buildVersion"), str):
                raise ValueError("Malformed Firebase buildVersion")
            codes.append(version_code(release["buildVersion"]))
        page_token = page.get("nextPageToken", "")
        if not isinstance(page_token, str) or page_token in seen_tokens:
            raise ValueError("Invalid Firebase pagination token")
        if not page_token:
            return codes
        seen_tokens.add(page_token)


def verify(apk, name, code):
    if metadata(apk) != (code, name):
        raise ValueError("APK version does not match validated release inputs")
    with ZipFile(apk) as archive:
        abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and len(name.split("/")) > 2}
    if abis != {"arm64-v8a"}:
        raise ValueError(f"Unexpected native ABIs: {sorted(abis)}")
    print("APK applicationId, versions and ABI verified")


if __name__ == "__main__":
    ref = os.environ["GITHUB_REF"]
    if os.environ["GITHUB_EVENT_NAME"] != "push":
        raise ValueError("Only push events may publish")
    if sys.argv[1] == "provenance":
        if ref == "refs/heads/dev":
            dev_provenance(os.environ["GITHUB_SHA"])
            notes = f"CLHS Pocket dev\n\nCommit: {os.environ['GITHUB_SHA']}\n"
        elif ref.startswith("refs/tags/"):
            notes = provenance(ref.removeprefix("refs/tags/"), Path("CHANGELOG.md").read_text(encoding="utf-8"))
        else:
            raise ValueError("Unexpected publication ref")
        (Path(os.environ["RUNNER_TEMP"]) / "release-notes.md").write_text(notes, encoding="utf-8")
    elif sys.argv[1] == "firebase-config":
        app_id = firebase_app(json.loads(Path("android/app/google-services.json").read_text(encoding="utf-8")), os.environ.get("CONFIGURED_FIREBASE_APP_ID", ""))
        group = os.environ.get("CONFIGURED_FIREBASE_GROUP", "") or "dev-testers"
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{3,62}", group):
            raise ValueError("Invalid Firebase tester group alias")
        with Path(os.environ["GITHUB_ENV"]).open("a", encoding="utf-8") as output:
            output.write(f"FIREBASE_APP_ID={app_id}\nFIREBASE_GROUP={group}\n")
    elif sys.argv[1] == "history":
        previous, latest = github_history(ref)
        previous += firebase_history(os.environ["FIREBASE_APP_ID"], os.environ["FIREBASE_ACCESS_TOKEN"])
        target = release_target(os.environ["GITHUB_EVENT_NAME"], ref, os.environ["GITHUB_RUN_NUMBER"], os.environ["GITHUB_SHA"], latest)
        check_upgrade(target.version_code, previous)
        with Path(os.environ["GITHUB_ENV"]).open("a", encoding="utf-8") as output:
            output.write(f"RELEASE_CHANNEL={target.channel}\nRELEASE_VERSION_NAME={target.version_name}\nRELEASE_VERSION_CODE={target.version_code}\n")
    elif sys.argv[1] == "verify":
        verify(Path(os.environ["APK_PATH"]), os.environ["RELEASE_VERSION_NAME"], version_code(os.environ["RELEASE_VERSION_CODE"]))
    else:
        raise ValueError("Unknown release guard command")
