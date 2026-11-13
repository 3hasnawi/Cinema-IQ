"""Publish an approved existing universal IPA, never rebuild or replace a release."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import zipfile

SOURCE = "3hasnawi/Cinema-IQ"
PUBLIC = "3hasnawi/Cinema-IQ-Releases"


def inspect_ipa(ipa, expected_digest):
    assert re.fullmatch(r"[0-9a-f]{64}", expected_digest), "Invalid expected SHA-256"
    assert hashlib.sha256(ipa.read_bytes()).hexdigest() == expected_digest, "Unexpected IPA bytes"
    with zipfile.ZipFile(ipa) as archive:
        assert archive.testzip() is None
        info = plistlib.loads(archive.read("Payload/CinemaIQ.app/Info.plist"))
        assert info["CFBundleIdentifier"] == "com.cinemaplus.rebuilt.ios"
        assert info["CFBundleSupportedPlatforms"] == ["iPhoneOS"]
        assert info["UIDeviceFamily"] == [1, 2]
        assert not info.get("UIRequiresFullScreen", False)
        assert set(info["UISupportedInterfaceOrientations~ipad"]) == {
            "UIInterfaceOrientationPortrait", "UIInterfaceOrientationPortraitUpsideDown",
            "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"}
        assert not any("UIPlayerFixture" in n or "player-pattern.mp4" in n or "CinemaIQUITests" in n
                       for n in archive.namelist()), "UI fixture must not be exported"
        binary = archive.read("Payload/CinemaIQ.app/" + info["CFBundleExecutable"])
        assert binary[:4] == bytes.fromhex("cffaedfe")
        assert int.from_bytes(binary[4:8], "little") == 0x100000c, "ARM64 required"
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", info["CFBundleShortVersionString"])
    assert re.fullmatch(r"[0-9]+", str(info["CFBundleVersion"]))
    return info


def gh(token, *args, allow_missing=False):
    result = subprocess.run(["gh", *args], env=dict(os.environ, GH_TOKEN=token),
                            text=True, capture_output=True)
    if result.returncode:
        if allow_missing and "HTTP 404" in result.stderr:
            return None
        # Avoid logging download redirects or signed query parameters from a failed CLI call.
        raise RuntimeError("GitHub operation failed; inspect its API status without exposing credentials")
    return result.stdout


def api(token, path, allow_missing=False):
    result = gh(token, "api", path, allow_missing=allow_missing)
    return json.loads(result) if result is not None else None


def main():
    source_token = os.environ["GH_TOKEN"]
    public_token = os.environ.get("PUBLIC_RELEASE_TOKEN", "")
    assert public_token, "RELEASES_TOKEN is not configured"
    assert os.environ["GITHUB_REPOSITORY"] == SOURCE
    run_id = os.environ["IOS_SOURCE_RUN_ID"]
    assert re.fullmatch(r"[0-9]+", run_id)
    digest = os.environ["IOS_EXPECTED_SHA256"]
    run = api(source_token, f"repos/{SOURCE}/actions/runs/{run_id}")
    assert run["status"] == "completed" and run["conclusion"] == "success"
    assert run["head_branch"] == "main" and run["event"] == "workflow_dispatch"
    assert run["path"] == ".github/workflows/ios-unsigned-ipa.yml"
    artifacts = api(source_token, f"repos/{SOURCE}/actions/runs/{run_id}/artifacts?per_page=100")
    matches = [a for a in artifacts["artifacts"]
               if a["name"].startswith("Cinema-iQ-iPhone-unsigned-") and not a["expired"]]
    assert len(matches) == 1, "Exactly one retained iOS artifact is required"
    root = Path(os.environ["RUNNER_TEMP"]) / "ios-publish"
    root.mkdir()
    gh(source_token, "run", "download", run_id, "--repo", SOURCE,
       "--name", matches[0]["name"], "--dir", str(root))
    original = root / "Cinema-iQ-iPhone-unsigned.ipa"
    info = inspect_ipa(original, digest)
    version = info["CFBundleShortVersionString"]
    ipa = root / f"Cinema-iQ-iPhone-iPad-{version}-unsigned.ipa"
    original.rename(ipa)
    sums = root / "SHA256SUMS.txt"
    sums.write_text(f"{digest}  {ipa.name}\n")
    notes = os.environ["IOS_RELEASE_NOTES"]
    assert run["head_sha"] in notes, "Release notes must identify the actual build source"
    notes_file = root / "notes.md"
    notes_file.write_text(notes)
    tag = f"ios-v{version}"
    latest_before = api(public_token, f"repos/{PUBLIC}/releases/latest")["id"]
    releases = api(public_token, f"repos/{PUBLIC}/releases?per_page=100")
    matching = [r for r in releases if r["tag_name"] == tag]
    assert len(matching) <= 1
    if not matching:
        gh(public_token, "release", "create", tag, "--repo", PUBLIC, "--target", "main",
           "--title", f"Cinema iQ iPhone & iPad {version}", "--notes-file", str(notes_file),
           "--draft", "--prerelease", "--latest=false")
        matching = [r for r in api(public_token, f"repos/{PUBLIC}/releases?per_page=100")
                    if r["tag_name"] == tag]
    assert len(matching) == 1
    release = matching[0]
    assert run["head_sha"] in (release["body"] or ""), "Draft belongs to another source build"
    expected = {ipa.name: (ipa.stat().st_size, digest),
                sums.name: (sums.stat().st_size, hashlib.sha256(sums.read_bytes()).hexdigest())}
    for asset in release["assets"]:
        assert asset["name"] in expected, "Unexpected existing asset requires review"
        size, sha = expected[asset["name"]]
        assert asset["size"] == size and asset.get("digest") == "sha256:" + sha
    if release["draft"]:
        uploaded = {a["name"] for a in release["assets"]}
        missing = [str(root / name) for name in expected if name not in uploaded]
        if missing:
            gh(public_token, "release", "upload", tag, "--repo", PUBLIC, *missing)
    release = api(public_token, f"repos/{PUBLIC}/releases/{release['id']}")
    assets = {a["name"]: a for a in release["assets"]}
    assert set(assets) == set(expected)
    for name, (size, sha) in expected.items():
        assert assets[name]["size"] == size
        assert assets[name].get("digest") == "sha256:" + sha, "Uploaded digest differs"
    if release["draft"]:
        gh(public_token, "release", "edit", tag, "--repo", PUBLIC, "--draft=false",
           "--prerelease", "--latest=false", "--notes-file", str(notes_file))
    published = api(public_token, f"repos/{PUBLIC}/releases/tags/{tag}")
    assert not published["draft"] and published["prerelease"]
    assert api(public_token, f"repos/{PUBLIC}/releases/latest")["id"] == latest_before
    url = f"https://github.com/{PUBLIC}/releases/tag/{tag}"
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        summary.write(f"Published universal iOS {version} ({info['CFBundleVersion']})\n\n{url}\n\n")
        summary.write(f"Source: `{run['head_sha']}`\n\nSHA-256: `{digest}`\n")
    print(f"Published verified universal iOS {version}; SHA-256 {digest}")


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--verify-only":
        metadata = inspect_ipa(Path(sys.argv[2]), sys.argv[3])
        print("Verified universal iOS", metadata["CFBundleShortVersionString"], metadata["CFBundleVersion"])
    else:
        assert len(sys.argv) == 1
        main()
