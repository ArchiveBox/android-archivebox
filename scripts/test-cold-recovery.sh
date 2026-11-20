#!/usr/bin/env bash
# Exercise recovery in order on the already installed disposable emulator.
set +x
set -euo pipefail
server=${1:?Usage: test-cold-recovery.sh SCREENSHOT_SERVER_DATA}
server_url=$(cat "$server/server-url")
# Never use this driver against an external server or non-emulator device.
[[ "$server_url" =~ ^http://127\.0\.0\.1:[0-9]+/?$ ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
password=$(cat "$server/recovery-test-password")
[[ "$password" =~ ^[0-9a-f]{48}$ ]]
if [[ "${GITHUB_ACTIONS:-}" == true ]]; then printf '::add-mask::%s\n' "$password"; fi
app=io.archivebox.app
runner=$app.test/androidx.test.runner.AndroidJUnitRunner

run_phase() {
    local method=$1 result
    shift
    # Keep runner diagnostics out of CI logs: failed UI assertions may include input data.
    if ! result=$(adb shell am instrument -w -r -e class "$app.ColdRecoveryTest#$method" "$@" "$runner" 2>&1) ||
       ! grep -Fxq 'OK (1 test)' <<< "${result//$'\r'/}"; then
        printf 'Android cold recovery phase failed: %s\n' "$method" >&2
        return 1
    fi
    printf 'Android cold recovery phase passed: %s\n' "$method"
}

[[ "$(adb shell pm clear "$app" | tr -d '\r')" == Success ]]
run_phase rejectedKeySignInPersistsAcrossLaunches \
    -e serverUrl "$server_url" -e recoveryFixtureUser android-captures -e recoveryFixturePassword "$password"
adb shell am force-stop "$app"
run_phase restoredKeyAfterProcessRestart
