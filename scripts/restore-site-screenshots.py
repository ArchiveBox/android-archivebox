#!/usr/bin/env -S uv run --no-project python
"""Restore verified Android captures without waiting for a new app build."""

import argparse
import hashlib
import json
import re
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen

REPO = "ArchiveBox/android-archivebox"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("destination", type=Path)
args = parser.parse_args()
args.destination.mkdir(parents=True, exist_ok=True)


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".github/pages"))
import artifacts


def restore_artifact():
    for run in artifacts.runs(REPO, "ci.yml", "main"):
        if "site-screenshots" in artifacts.names(REPO, run):
            artifacts.download(REPO, run, "site-screenshots", args.destination)
            return run
    return None


def restore_local():
    source = Path(__file__).resolve().parent.parent / "docs" / "screenshots"
    raw = (source / "manifest.json").read_bytes()
    manifest = json.loads(raw)
    if manifest.get("workflowRun"):
        raise ValueError("Local bootstrap must retain its original local provenance")
    for capture in manifest["screenshots"]:
        name = capture["file"]
        if not re.fullmatch(r"[a-z0-9-]+\.png", name):
            raise ValueError("Unsafe local capture filename")
        shutil.copyfile(source / name, args.destination / name)
    (args.destination / "manifest.json").write_bytes(raw)
    metadata = {
        "source": "checked-in-local",
        "commit": manifest["commit"],
        "manifestSHA256": hashlib.sha256(raw).hexdigest(),
    }
    (args.destination / "capture-run.json").write_text(json.dumps(metadata) + "\n")
    print(
        f"Restored local Android captures from {manifest['commit']} (app {manifest['appVersion']}); no hosted CI run claimed"
    )
    raise SystemExit(0)


run = restore_artifact()
if run is None:
    # Bootstrap before the first Pages deployment, including while the custom
    # domain certificate is still being provisioned. GitHub confirms no prior
    # deployment exists; TLS or fetch failures on an existing site still fail.
    if not artifacts.api(REPO, "deployments?environment=github-pages&per_page=1"):
        restore_local()
    # Published captures remain usable after Actions artifacts expire.
    base = "https://android.archivebox.io/screenshots/"
    try:
        with urlopen(base + "manifest.json", timeout=60) as response:
            raw = response.read()
    except HTTPError as error:
        if error.code != 404:
            raise
        restore_local()
    manifest = json.loads(raw)
    if not manifest.get("workflowRun"):
        restore_local()
    match = re.fullmatch(
        r"https://github\.com/ArchiveBox/android-archivebox/actions/runs/([1-9]\d*)",
        manifest["workflowRun"]["url"],
    )
    if not match:
        raise ValueError("Unexpected published capture run URL")
    run = artifacts.api(REPO, f"actions/runs/{match[1]}")
    if not artifacts.trusted(run, REPO, "ci.yml", "main"):
        raise ValueError("Published captures must come from successful main CI")

    def fetch(capture):
        name = capture["file"]
        if not re.fullmatch(r"[a-z0-9-]+\.png", name):
            raise ValueError("Unsafe capture filename")
        with urlopen(base + name, timeout=60) as response:
            (args.destination / name).write_bytes(response.read())

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(fetch, manifest["screenshots"]))
    (args.destination / "manifest.json").write_bytes(raw)

# This artifact was uploaded only after the original CI job checked its exact
# source revision, release version, every required screen and every PNG digest.
metadata = {"commit": run["head_sha"], "runURL": run["html_url"]}
(args.destination / "capture-run.json").write_text(json.dumps(metadata) + "\n")
print(f"Restored verified Android captures from run {run['id']} ({run['head_sha']})")
