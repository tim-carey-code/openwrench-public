# Android development

Use JDK 17 and Android SDK platform 35/build-tools 35.0.0. Open `kotlin/` in
Android Studio or use its checked-in Gradle wrapper:

```bash
cd kotlin
./gradlew :shared:jvmTest :shared:testAndroidHostTest :shared:assembleAndroidMain \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
```

The debug APK is `kotlin/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
GitHub CI checks are currently disabled; local validation is the readiness
requirement (Tim, 2026-11-20). Retain exact-commit commands, results, APK identity
and report locations in the PR handoff; do not require an Actions run or re-enable
workflows unless requested. See [the validation policy](../AGENTS.md#development-checks).
No Python
installation, package manager, source generator, HTTP server or desktop tooling
is part of this workflow.

Tests cover fixed simulator data, generated decoder reference cases, session
capability/allowlist validation, transport framing, cleanup, polling, lifecycle
and snapshot document validation. Test transports are scripted; automated tests
must never open a physical adapter. See `AGENTS.md` for repository constraints.

Preserve reference fixtures in reviews. `ObdReferenceFixtures.kt` records the
reference source SHA-256; the underlying original source is inspectable at
Git commit `f1d0e3c1dffe701f7efa1b3c7a04a53150e1eee5`. Fixture data are compiled
or read directly by Kotlin tests; regenerating them is not a build dependency.

Keep Android local configuration and build outputs untracked. Check UI behavior
on an emulator or device after changes and preserve the existing visual design.
Only explicit owner-operated tests can provide physical adapter/vehicle evidence;
record their exact scope without generalizing to other adapters or functions.

When the retained signing key is unavailable, add `-PopenwrenchUnsigned=true` to
these checks. Generated APKs are unsigned and not installable. Intended updates
use `-PopenwrenchRequireRetainedKey=true` plus the certificate/history gates in
[signing and safe updates](signing-and-safe-updates.md). Do not replace an
installed preview's signing identity with a newly generated debug key.

## Manual visual acceptance

Tim or Nick checks UI changes using the [exact-head APK checklist](manual-visual-review.md)
and records the result on the PR. General visual CI was deliberately removed
under issue #120; do not restore it as an automatic requirement. Native
tests/lint/build and relevant saved-drive replay checks must be validated locally
and do not replace human visual acceptance. Optional `scripts/capture-*.mjs` helpers and their
synthetic assertion tests remain available locally, without an automatic runner.
