# Automatic scan retention and in-place APK upgrade verification

## Diagnostic scan behavior

A completed **hardware diagnostic scan** now saves to Advanced → Individual records → Scans
automatically. This happens on successful publication even when the Diagnostics
page is not open. Guided idle scans publishing a hardware diagnostic capture use
the same behavior. A capture in progress, canceled or failed does not create
a saved record. The process-owned scan history coordinator serializes the
initial storage read and all writes, and capture UUID makes duplicate delivery
and Activity recreation idempotent.
The process-owned observer ignores in-progress and unavailable states before
deduplicating captures, so canceling or failing a later scan cannot restore a
previously deleted scan. Manual Save / retry remains available.
The observer reads the process-owned controller directly, avoiding an Activity
reference in its long-lived coroutine across Activity recreation.

Automatic saves omit the VIN even when it was explicitly read. The Diagnostics
page retains a manual **Save scan / retry** action; selecting an acquisition VIN
and saving enriches the *same* row (no new scan UUID, no lost case linkage).
Imported, simulator, and synthetic evidence are never saved automatically.
Automatic saves use the existing app-private history, schema 2 typed envelopes,
50-scan / 2 MB limits, and atomic writes. Full/corrupt/future storage surfaces
an error and is never deleted, rotated silently or replaced with empty history.
Case selection still requires explicit user confirmation that one saved scan
and one completed saved drive belong to the same vehicle.

The completed scan's source, code services, typed readings, frame-zero limits,
and original timestamps are unchanged. No extra vehicle commands, code clearing,
background diagnostics or network access are added. Manual deletion remains
available. The dashboard copy/export paths remain distinct from local saving.

## APK update policy

Normal Android package ID: `org.openwrench.android`.
USB preview package ID: `org.openwrench.android.usbpreview`.
They store **separate** files. No app-private history is shared between them.
This change advances the normal preview version to `versionCode=7` /
`0.3.5-native-preview` (the USB preview variant retains its own package ID).

For an in-place update, the package ID and signing certificate must match.
Android will reject an update with a different certificate; do not work around
that failure by uninstalling, since uninstall/clear storage deletes app-private
scans, drives and cases. Android automatic backup is disabled.
Never use a downgrade to check newer stores: older APKs may be unable to read
newer scan/drive/case schema versions.

### Regression coverage

`ScanAutoSaveTest` covers an automatic scan queued during startup history load,
duplicate capture delivery/reopen, explicit VIN opt-in on the same record,
import/simulator exclusion, unreadable history preserved without overwrite,
and full scan/drive/case history reopen in the same app-private directory.
Observer regressions cover delete scan A → start scan B → cancel or fail B,
followed by successfully publishing a new capture and verifying it saves once.
Existing structured-case tests cover older scan schema 1, typed scan schema 2,
drive archive migrations and immutable saved case evidence.

Coordinator tests use `runCurrent()` to execute the pending work on their shared
test scheduler: `advanceUntilIdle()` alone does not drain `backgroundScope`.
Save/delete admission tests also check that history becomes busy before the
writer coroutine starts, so a conflicting UI action cannot slip into that gap.

From `kotlin/`, run:

```sh
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug
```

### Owner-controlled update verification

The update verifier requires a trusted reference APK, exact package and public
certificate fingerprint, and supports Live Home Preview. It verifies both APK
signatures and the actually installed APK before offering an in-place update.
See [signing and safe updates](signing-and-safe-updates.md) for the new required
arguments, unsigned builds, retained-key recovery and owner-approved history
check. Do not use the older invocation or uninstall/clear storage to work around
a signing mismatch. Passing CI does not prove a matching signing certificate.
Byte preservation before first launch and manual history reopening remain
separate gates; neither establishes physical hardware qualification.


The owner-operated inventory also hashes `files/investigations-v1/*.json`.
Synthetic tool-mocked checks cover investigation bytes and hash failures; actual
retained-certificate installation and post-launch reopening remain separately
authorized owner-operated gates. Never clear history to make them pass.
