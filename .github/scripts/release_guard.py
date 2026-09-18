"""Fail-closed release checks. Uses only Python, Git, gh and Android build tools."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
from zipfile import ZipFile


# Public certificate verified from the published v2.0.0 APK, not a secret.
CERTIFICATE = "78217e39861a103977ef7f7aaee06b045627adad315557668bce12c02f82431d"


def run(*args):
    return subprocess.check_output(args, text=True, encoding="utf-8").strip()


def version(tag):
    if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag):
        raise ValueError("Expected a stable vMAJOR.MINOR.PATCH tag")
    return tuple(map(int, tag[1:].split(".")))


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
    if not previous or not max(previous) < code <= 2100000000:
        raise ValueError("versionCode must exceed every published APK (and remain <= 2100000000)")


def metadata(apk):
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / os.environ.get("BUILD_TOOLS_VERSION", "37.0.0")
    suffix = ".exe" if os.name == "nt" else ""
    badging = run(str(tools / f"aapt{suffix}"), "dump", "badging", str(apk))
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    if not match or match[1] != "com.clhs.score":
        raise ValueError("Unexpected APK applicationId or unreadable metadata")
    return int(match[2]), match[3]


def history(tag, code):
    # Inspect all published APKs, including prereleases; never infer codes from tag names.
    pages = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{os.environ['GITHUB_REPOSITORY']}/releases?per_page=100"))
    previous = []
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "previous.apk"
        for release in (release for page in pages for release in page):
            if release["draft"]:
                continue
            if release["tag_name"] == tag:
                raise ValueError("Published releases are immutable; use a new version tag")
            if not release["prerelease"] and version(tag) <= version(release["tag_name"]):
                raise ValueError("Release version must exceed all published stable versions")
            assets = [asset for asset in release["assets"] if asset["name"].endswith(".apk")]
            if not assets:
                raise ValueError("Published release has no APK; cannot establish versionCode history")
            for asset in assets:
                url = asset["browser_download_url"]
                if not url.startswith(f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/download/"):
                    raise ValueError("Unexpected release asset URL")
                with urllib.request.urlopen(url, timeout=120) as response:
                    data = response.read()
                if asset.get("digest") != "sha256:" + hashlib.sha256(data).hexdigest():
                    raise ValueError("Missing or mismatched release asset SHA-256")
                apk.write_bytes(data)
                old_code, old_name = metadata(apk)
                print(f"Published {release['tag_name']}: versionCode={old_code}, versionName={old_name}")
                previous.append(old_code)
    check_upgrade(code, previous)


def verify(apk, tag, code):
    if metadata(apk) != (code, tag[1:]):
        raise ValueError("APK version does not match validated release inputs")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / os.environ.get("BUILD_TOOLS_VERSION", "37.0.0")
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    result = run(str(signer), "verify", "--verbose", "--print-certs", str(apk))
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", result, re.MULTILINE)
    if certificates != [CERTIFICATE]:
        raise ValueError("APK signer differs from the published production certificate")
    with ZipFile(apk) as archive:
        abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and len(name.split("/")) > 2}
    if abis != {"arm64-v8a"}:
        raise ValueError(f"Unexpected native ABIs: {sorted(abis)}")
    print("APK applicationId, versions, signature, certificate and ABI verified")


if __name__ == "__main__":
    tag = os.environ["GITHUB_REF_NAME"]
    code = int(os.environ["GITHUB_RUN_NUMBER"])
    version(tag)
    if sys.argv[1] == "provenance":
        notes = provenance(tag, Path("CHANGELOG.md").read_text(encoding="utf-8"))
        (Path(os.environ["RUNNER_TEMP"]) / "release-notes.md").write_text(notes, encoding="utf-8")
    elif sys.argv[1] == "history":
        history(tag, code)
    elif sys.argv[1] == "verify":
        verify(Path(os.environ["APK_PATH"]), tag, code)
    else:
        raise ValueError("Unknown release guard command")
