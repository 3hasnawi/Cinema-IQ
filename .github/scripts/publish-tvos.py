"""Publish an already-built TV IPA with separate source and public-repository credentials."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import zipfile

SOURCE = "3hasnawi/Cinema-IQ"
PUBLIC = "3hasnawi/Cinema-IQ-Releases"
source_token = os.environ["GH_TOKEN"]
public_token = os.environ.get("PUBLIC_RELEASE_TOKEN", "")
assert public_token, "The repository RELEASES_TOKEN secret is not configured"
assert os.environ["GITHUB_REPOSITORY"] == SOURCE
run_id = os.environ["TV_SOURCE_RUN_ID"]
assert re.fullmatch(r"[0-9]+", run_id), "Invalid build run ID"
root = Path(os.environ["RUNNER_TEMP"]) / "tv-publish"
root.mkdir()


def gh(token, *args, allow_missing=False):
    env = dict(os.environ, GH_TOKEN=token)
    result = subprocess.run(["gh", *args], env=env, text=True, capture_output=True)
    if result.returncode:
        if allow_missing and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(result.stderr)
    return result.stdout


def api(token, path, allow_missing=False):
    result = gh(token, "api", path, allow_missing=allow_missing)
    return json.loads(result) if result is not None else None


run = api(source_token, f"repos/{SOURCE}/actions/runs/{run_id}")
assert run["status"] == "completed" and run["conclusion"] == "success"
assert run["head_branch"] == "main" and run["event"] == "workflow_dispatch"
assert run["path"] == ".github/workflows/tvos-unsigned-ipa.yml"
gh(source_token, "run", "download", run_id, "--repo", SOURCE,
   "--name", f"Cinema-iQ-AppleTV-{run_id}", "--dir", str(root))
metadata = json.loads((root / "build-info.json").read_text())
version = metadata["version"]
assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
assert metadata["sourceCommit"] == run["head_sha"]
assert metadata["bundleIdentifier"] == "com.cinemaplus.rebuilt.tv"
assert metadata["platform"] == "tvOS" and metadata["architecture"] == "arm64"
assert metadata["signing"] == "unsigned"
ipa = root / f"Cinema-iQ-AppleTV-{version}-unsigned.ipa"
digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
assert (root / "SHA256SUMS.txt").read_text().split() == [digest, ipa.name]
with zipfile.ZipFile(ipa) as archive:
    assert archive.testzip() is None
    info = plistlib.loads(archive.read("Payload/CinemaIQTV.app/Info.plist"))
    assert info["CFBundleIdentifier"] == metadata["bundleIdentifier"]
    assert info["CFBundleShortVersionString"] == version
    assert str(info["CFBundleVersion"]) == str(metadata["build"])
    assert info["CFBundleSupportedPlatforms"] == ["AppleTVOS"]
    assert info["UIDeviceFamily"] == [3]
tag = f"tvos-v{version}"
public_url = f"https://github.com/{PUBLIC}/releases/tag/{tag}"
notes = os.environ["TV_RELEASE_NOTES"]
assert metadata["sourceCommit"] in notes, "Notes must identify the validated source commit"
public_notes = root / "public-notes.md"
public_notes.write_text(notes)
mirror_notes = root / "mirror-notes.md"
mirror_notes.write_text(notes + f"\nPublic download: {public_url}\n")


def verify_assets(token, repo, release):
    assets = {a["name"]: a for a in release["assets"]}
    assert set(assets) == {ipa.name, "SHA256SUMS.txt", "build-info.json"}
    assert assets[ipa.name]["size"] == ipa.stat().st_size
    server_digest = assets[ipa.name].get("digest")
    if server_digest:
        assert server_digest == "sha256:" + digest
    else:
        target = root / ("verify-" + repo.replace("/", "-"))
        if not target.exists():
            gh(token, "release", "download", tag, "--repo", repo,
               "--pattern", ipa.name, "--dir", str(target))
        assert hashlib.sha256((target / ipa.name).read_bytes()).hexdigest() == digest


def find_release(token, repo):
    # GitHub's by-tag endpoint excludes drafts; a draft must be found in the owner's release list.
    endpoint = f"repos/{repo}/releases/tags/{tag}"
    release = api(token, endpoint, allow_missing=True)
    if release is not None:
        return release
    drafts = [r for r in api(token, f"repos/{repo}/releases?per_page=100")
              if r["tag_name"] == tag and r["draft"]]
    assert len(drafts) <= 1, "Multiple drafts for this tag require review before uploading"
    return api(token, f"repos/{repo}/releases/{drafts[0]['id']}") if drafts else None


def stage(token, repo, target, notes_file):
    release = find_release(token, repo)
    if release is None:
        gh(token, "release", "create", tag, "--repo", repo, "--target", target,
           "--title", f"Cinema iQ Apple TV {version}", "--notes-file", str(notes_file),
           "--draft", "--latest=false")
        release = find_release(token, repo)
    assert release is not None, "The new draft is not visible yet"
    # Resume only a draft of this exact build. Published releases are verified, never overwritten.
    assert metadata["sourceCommit"] in (release["body"] or "")
    if release["draft"]:
        expected = {ipa.name, "SHA256SUMS.txt", "build-info.json"}
        assert {a["name"] for a in release["assets"]} <= expected
        gh(token, "release", "upload", tag, "--repo", repo, "--clobber",
           str(ipa), str(root / "SHA256SUMS.txt"), str(root / "build-info.json"))
        release = api(token, f"repos/{repo}/releases/{release['id']}")
    verify_assets(token, repo, release)
    return release


public_release = stage(public_token, PUBLIC, "main", public_notes)
source_release = stage(source_token, SOURCE, run["head_sha"], mirror_notes)
for token, repo, release, latest in [(public_token, PUBLIC, public_release, "false"),
                                      (source_token, SOURCE, source_release, "true")]:
    if release["draft"]:
        gh(token, "release", "edit", tag, "--repo", repo, "--draft=false",
           "--prerelease=false", f"--latest={latest}")
    published = api(token, f"repos/{repo}/releases/tags/{tag}")
    assert not published["draft"] and not published["prerelease"]
    verify_assets(token, repo, published)
assert api(public_token, f"repos/{PUBLIC}/releases/latest")["tag_name"] != tag
with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
    summary.write(f"Published Apple TV {version} ({metadata['build']})\n\n{public_url}\n\n")
    summary.write(f"atvloadly mirror: https://github.com/{SOURCE}/releases/tag/{tag}\n\n")
    summary.write(f"SHA-256: `{digest}`\n")
print(f"Published verified Apple TV {version}; SHA-256 {digest}")
