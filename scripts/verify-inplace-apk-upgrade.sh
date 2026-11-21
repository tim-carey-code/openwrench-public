#!/usr/bin/env bash
# Owner-operated, separately authorized verification. Never run automatically.
# Usage: bash scripts/verify-inplace-apk-upgrade.sh <candidate.apk> <reference.apk> <package-id> <trusted-cert-sha256>
set -euo pipefail
APK="${1:?Provide the candidate APK}"
REFERENCE="${2:?Provide the delivered reference APK}"
PACKAGE="${3:?Provide the exact installed package ID}"
CERTIFICATE="${4:?Provide the trusted certificate SHA-256 fingerprint}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
for command in node adb aapt apksigner sha256sum diff; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Missing required command: $command" >&2
        exit 2
    fi
done
# Require actual signatures and compatible debug history access before contacting a device.
node "$SCRIPT_DIR/check-apk-update.mjs" "$REFERENCE" "$APK" "$PACKAGE" "$CERTIFICATE" --require-debuggable
mapfile -t devices < <(adb devices | awk 'NR > 1 && NF { print $1 " " $2 }')
if [[ "${#devices[@]}" != 1 || "${devices[0]##* }" != device ]]; then
    echo "STOP: Connect exactly one authorized Android device." >&2
    exit 3
fi
SERIAL="${devices[0]% *}"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
adb -s "$SERIAL" shell pm path "$PACKAGE" | tr -d '\r' > "$work_dir/package-paths"
mapfile -t paths < "$work_dir/package-paths"
if [[ "${#paths[@]}" != 1 || "${paths[0]}" != package:/* ]]; then
    echo "STOP: Expected one installed APK; fresh/split installs are not supported." >&2
    exit 3
fi
adb -s "$SERIAL" pull "${paths[0]#package:}" "$work_dir/installed.apk" >/dev/null
node "$SCRIPT_DIR/check-apk-update.mjs" "$work_dir/installed.apk" "$APK" "$PACKAGE" "$CERTIFICATE" --require-debuggable

echo "Park the vehicle, finish monitoring, and close OpenWrench normally."
echo "This only replaces the selected package in place; it never uninstalls, clears data or downgrades."
read -r -p "Type UPDATE to verify history hashes and install in place: " approval
if [[ "$approval" != UPDATE ]]; then echo "Canceled."; exit 1; fi
remote_scan='for f in files/scan-history-v1.json files/diagnostic-cases-v1.json files/investigations-v1/*.json files/drive-history-v1/*.json files/diagnoses-v1/*/manifest.json files/diagnoses-v1/*/readings.csv files/diagnoses-v1/*/drives/*.json; do if [ -f "$f" ]; then sha256sum "$f" || exit 1; fi; done'
inventory() {
    adb -s "$SERIAL" shell "run-as $PACKAGE sh -c '$remote_scan'" | tr -d '\r' | LC_ALL=C sort
}
inventory > "$work_dir/before"
if [[ ! -s "$work_dir/before" ]]; then
    echo "STOP: No readable saved history; do not test preservation with a fresh/empty install." >&2
    exit 3
fi
adb -s "$SERIAL" install -r "$APK"
inventory > "$work_dir/after"
if ! diff -u "$work_dir/before" "$work_dir/after"; then
    echo "FAIL: History bytes changed. Preserve the device; do not clear data or uninstall." >&2
    exit 4
fi
echo "PASS: APK identity verified and saved history bytes match before first launch."
echo "NEXT: Manually reopen investigations, diagnoses, scans, drives and cases. This does not prove UI/schema compatibility after launch."
