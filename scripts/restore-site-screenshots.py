#!/usr/bin/env python3
"""Restore verified Android captures without waiting for a new app build."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import subprocess
from urllib.error import HTTPError
from urllib.request import urlopen

REPO = "ArchiveBox/android-archivebox"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("destination", type=Path)
args = parser.parse_args()
args.destination.mkdir(parents=True, exist_ok=True)


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", f"repos/{REPO}/{path}"]))


def trusted(run):
    return (
        run["head_repository"]["full_name"] == REPO
        and run["head_branch"] == "main"
        and run["event"] in ("push", "workflow_dispatch")
        and run["path"] == ".github/workflows/ci.yml"
        and run["conclusion"] == "success"
    )


def restore_artifact():
    page = 1
    while True:
        runs = api(f"actions/workflows/ci.yml/runs?branch=main&status=success&per_page=50&page={page}")["workflow_runs"]
        for run in runs:
            if not trusted(run):
                continue
            rows = subprocess.check_output([
                "gh", "api", f"repos/{REPO}/actions/runs/{run['id']}/artifacts?per_page=100",
                "--paginate", "--jq", ".artifacts[] | @json",
            ], text=True)
            if any(a["name"] == "site-screenshots" and not a["expired"] for line in rows.splitlines() if (a := json.loads(line))):
                subprocess.run(["gh", "run", "download", str(run["id"]), "--repo", REPO,
                                "--name", "site-screenshots", "--dir", str(args.destination)], check=True)
                return run
        if len(runs) < 50:
            return None
        page += 1


run = restore_artifact()
if run is None:
    # Bootstrap before the first Pages deployment, including while the custom
    # domain certificate is still being provisioned. GitHub confirms no prior
    # deployment exists; TLS or fetch failures on an existing site still fail.
    if not api("deployments?environment=github-pages&per_page=1"):
        (args.destination / "capture-run.json").write_text('{"pending": true}\n')
        print("First Pages publication: gallery pending the first verified capture.")
        raise SystemExit(0)
    # Published captures remain usable after Actions artifacts expire.
    base = "https://android.archivebox.io/screenshots/"
    try:
        with urlopen(base + "manifest.json", timeout=60) as response:
            raw = response.read()
    except HTTPError as error:
        if error.code != 404:
            raise
        (args.destination / "capture-run.json").write_text('{"pending": true}\n')
        print("No verified gallery published yet; publish the website with a pending-gallery message.")
        raise SystemExit(0)
    manifest = json.loads(raw)
    match = re.fullmatch(r"https://github\.com/ArchiveBox/android-archivebox/actions/runs/([1-9]\d*)", manifest["workflowRun"]["url"])
    if not match:
        raise ValueError("Unexpected published capture run URL")
    run = api(f"actions/runs/{match[1]}")
    if not trusted(run):
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
