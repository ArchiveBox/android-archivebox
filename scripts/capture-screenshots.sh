#!/usr/bin/env bash
# Run the real UI suite against the prepared server, then export its device captures.
set -Eeuo pipefail
server=${1:?Usage: capture-screenshots.sh SERVER_DATA SCREENSHOTS_DIR [APK_DIR]}
output=${2:?A screenshot output directory is required}
apks=${3:-app/build/outputs/apk}
mkdir -p "$output" artifacts
collect_diagnostics() {
    mkdir -p artifacts/emulator-diagnostics
    adb logcat -b all -d > artifacts/logcat.txt 2>&1 || true
    adb shell dumpsys meminfo > artifacts/emulator-diagnostics/memory.txt 2>&1 || true
    adb shell getprop > artifacts/emulator-diagnostics/properties.txt 2>&1 || true
    adb shell df -h /data > artifacts/emulator-diagnostics/storage.txt 2>&1 || true
    adb exec-out screencap -p > artifacts/emulator-diagnostics/screen.png || true
}
on_error() {
    local failure_status=$?
    collect_diagnostics
    exit "$failure_status"
}
trap on_error ERR
curl --fail --silent --show-error --max-time 10 "$(cat "$server/server-url")/api/v1/openapi.json" > /dev/null
export BACKEND_REVISION
BACKEND_REVISION=$(cat "$server/backend-revision")
adb wait-for-device
adb reverse tcp:5759 tcp:5759
adb shell df -h /data
main_apk=$(find "$apks" -name '*debug.apk' ! -name '*androidTest*' -print -quit)
test_apk=$(find "$apks" -name '*androidTest.apk' -print -quit)
[[ -n "$main_apk" && -n "$test_apk" ]] || { echo 'Both app and test APKs are required' >&2; exit 1; }
# A fresh install also removes stale launcher-widget PendingIntents from prior runs.
if adb shell pm list packages io.archivebox.app | grep -q '^package:io.archivebox.app$'; then
    adb uninstall io.archivebox.app
fi
# Push APKs before invoking PackageManager: avoid its streamed-install pipe on API 37.
adb install --no-streaming "$main_apk"
adb install --no-streaming -r "$test_apk"
# Preserve real failures: instrumentation exit status alone does not report failed tests.
api_token=$(cat "$server/api-token")
if [[ "${GITHUB_ACTIONS:-}" == true ]]; then printf '::add-mask::%s\n' "$api_token"; fi
adb shell am instrument -w -r \
    -e class io.archivebox.app.ArchiveBoxJourneyTest,io.archivebox.app.WidgetJourneyTest,io.archivebox.app.VisualProfilesTest \
    -e serverUrl http://127.0.0.1:5759 \
    -e apiToken "$api_token" \
    io.archivebox.app.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/instrumentation.log
adb logcat -d > artifacts/logcat.txt
if ! grep -Eq '^OK \([1-9][0-9]* test' artifacts/instrumentation.log; then
    echo 'The real-device journey did not pass; refusing to publish screenshots.' >&2
    mkdir -p artifacts/failed-screenshots
    adb pull /sdcard/Android/data/io.archivebox.app/files/screenshots/. artifacts/failed-screenshots/ || true
    exit 1
fi
# Validate a fresh pull before copying anything into a potentially reused output directory.
# A missing new screenshot must never be replaced silently by an earlier run's PNG.
capture_stage=$(mktemp -d "${TMPDIR:-/tmp}/archivebox-android-capture.XXXXXX")
trap 'rm -rf "$capture_stage"' EXIT
adb pull /sdcard/Android/data/io.archivebox.app/files/screenshots/. "$capture_stage/"
export CAPTURE_OUTPUT="$capture_stage"
export CAPTURE_COMMIT
CAPTURE_COMMIT=$(git rev-parse HEAD)
uv run --no-project python - <<'PY'
import datetime, hashlib, json, os, pathlib, struct
root = pathlib.Path(os.environ['CAPTURE_OUTPUT'])
metadata = json.loads((root / 'device.json').read_text())
labels = {
    'dark-mode': ('A quieter view', 'Use the real Android dark appearance across the native app.'),
    'tablet': ('Room for your archive', 'Use adaptive navigation on an actual tablet-size Android display.'),
    'home': ('ArchiveBox at home', 'Navigate collection, server tools, and helpful resources.'),
    'crawls': ('Your crawls', 'Inspect actual crawl history on your server.'),
    'scheduled-crawls': ('Scheduled crawls', 'Manage recurring captures on the server.'),
    'archive-results': ('Captured output formats', 'Inspect real archive results and saved output files.'),
    'server-tags': ('Organize your collection', 'Manage the server collection tags.'),
    'ai-agent': ('AI Agent', 'Access the server AI interface and its current availability.'),
    'users': ('Users', 'Manage accounts on your own server.'),
    'personas': ('Personas', 'Manage server capture identities for logged-in websites.'),
    'api-keys': ('API keys', 'Review access keys in authenticated administration.'),
    'webhooks': ('Webhooks', 'Configure outbound server notifications.'),
    'processes': ('Processes', 'Inspect real server processes and their status.'),
    'machines': ('Machines', 'See machines registered with your ArchiveBox server.'),
    'network-interfaces': ('Network interfaces', 'Inspect server networking.'),
    'binaries': ('Installed binaries', 'See real capture executables available to the server.'),
    'plugins': ('Plugins', 'Inspect available archiving plugins.'),
    'workers': ('Workers', 'View current server workers.'),
    'logs': ('Server logs', 'Inspect actual server activity and diagnostics.'),
    'widget': ('ArchiveBox on your home screen', 'Save or search from a real installed Android home screen widget.'),
    'setup-docker': ('Run your own server', 'Follow the native Docker setup guide with the ready-to-copy command.'),
    'onboarding': ('Welcome to your archive', 'Choose an existing server or follow the setup guide.'),
    'connections': ('Your server, connected', 'Connect with your server address and securely stored API key.'),
    'discovery': ('Discover nearby servers', 'Look for ArchiveBox servers on port 5759 on your local network and configured tailnet.'),
    'library': ('Your saved web', 'Browse the real ArchiveBox collection and archived pages.'),
    'search': ('Find it again', 'Search saved pages by title, URL, or tags.'),
    'snapshot': ('Open an archived page', 'Read the preserved copy hosted on your ArchiveBox server.'),
    'add': ('Save a link', 'Send URLs and capture preferences to your server.'),
    'tags': ('Make it easy to find', 'Choose existing tags or add your own.'),
    'share': ('Share from any app', 'Receive a real Android share intent and review the URL before saving.'),
    'share-saved': ('Saved to your archive', 'Confirm the server accepted your link and its tags.'),
    'activity': ('Follow your collection', 'See server activity and archiving progress.'),
    'settings': ('Connection Settings', 'Manage your server and reopen the setup guide.'),
    'server-browser': ('Your server tools', 'Use authenticated ArchiveBox administration inside the app.'),
}
shots = []
for identifier, (title, description) in labels.items():
    path = root / (identifier + '.png')
    data = path.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', f'Not a PNG: {path}'
    width, height = struct.unpack('>II', data[16:24])
    assert width >= 320 and height >= 480, f'Invalid screenshot size: {path}'
    shots.append(dict(id=identifier, file=path.name, title=title, description=description,
                      width=width, height=height, sha256=hashlib.sha256(data).hexdigest()))
manifest = dict(schemaVersion=1, commit=os.environ['CAPTURE_COMMIT'], appVersion=metadata['appVersion'],
                generatedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), device=metadata['device'],
                backend=os.environ.get('BACKEND_REVISION', 'local ArchiveBox checkout'), requiredScreenshots=list(labels), screenshots=shots)
if os.environ.get('GITHUB_RUN_ID'):
    manifest['workflowRun'] = {'url': f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"}
(root / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(f'Validated {len(shots)} actual Android screenshots.')
PY
cp "$capture_stage"/* "$output/"
