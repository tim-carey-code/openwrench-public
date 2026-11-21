# Owner-managed signing and safe updates

The delivered GUI preview has separate storage under package
`org.openwrench.android.livehomepreview`, versionCode 7, minimum API 24.
Its previously verified certificate SHA-256 is:

`91dcbbea2c6f75af188caea1127754c33e9e14a42a7bf4f5ce9e90c01a07a31e`

The delivered APK SHA-256 is
`c55bc2f5f366a63ba98b8e90f079ae0033e00db13749fe48c3c4092986ddbca2`.
A reference artifact does not identify an unseen installed app. Its public
certificate cannot recover the private key. The original key was not found in
the resumed workspace, so a compatible signed update is currently blocked.
No replacement key is a recovery solution for an existing installation.

## Owner actions

1. Recover the original keystore from the build machine or owner-controlled
   backup. Do not send it/passwords in chat, commit it or attach it to artifacts.
   Inspect its public fingerprint locally with `keytool -list -v -keystore
   existing.keystore`; enter the password interactively. Compare the fingerprint
   above, using the actual key alias if selecting one.
2. Keep the matching key in durable owner-controlled storage with an encrypted
   backup and an established password manager. Record only public identity,
   alias, package/version and APK hashes in a release ledger. Any credential,
   CI-secret or signing-service configuration requires separate owner approval.
3. If recovery fails, a different package/key and a migration plan require an
   explicit decision. Saved-drive import is in-memory replay, not full history
   migration. Never uninstall or clear data to bypass a signing mismatch.
4. Choose the next version deliberately (normally greater than 7), retain the
   trusted reference, and approve publication/device updates separately. This
   change keeps versionCode 7 and existing storage schemas. Equal-code local
   updates pass preflight; downgrades do not.

## Build modes

When the original key is unavailable, run the repository-required checks with
`-PopenwrenchUnsigned=true`, including Live Home Preview:

```sh
cd kotlin
./gradlew :shared:jvmTest :shared:testAndroidHostTest :shared:assembleAndroidMain \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug \
  :androidApp:testLiveHomePreviewUnitTest :androidApp:lintLiveHomePreview \
  :androidApp:assembleLiveHomePreview -PopenwrenchUnsigned=true
```

The opt-in removes signing configurations for that invocation. Generated APKs
are **unsigned and not installable**; no replacement signing key is generated.
Once the owner separately configures the retained key, an intended update uses:

```sh
./gradlew :androidApp:assembleLiveHomePreview -PopenwrenchRequireRetainedKey=true
```

This opt-in checks the selected build type's actual keystore file before AGP can
create a missing default key. Existence is not proof of signer identity: use the
APK preflight below. Ordinary CI behavior stays unchanged. Fresh CI debug keys
must never be assumed compatible with an existing install. No credential or
repository secret is configured by these flags.

## Offline identity preflight

From the repository root, with Node, JDK and Android build-tools `aapt` and
`apksigner` on PATH:

```sh
node scripts/check-apk-update.mjs reference.apk candidate.apk \
  org.openwrench.android.livehomepreview \
  91dcbbea2c6f75af188caea1127754c33e9e14a42a7bf4f5ce9e90c01a07a31e
```

Both APK signatures are actually verified, then certificates are compared with
the trusted fingerprint. Exact package, nondecreasing versionCode and no increase
in minimum SDK are required. Only normal, USB Preview and Live Home Preview
packages are allowlisted. Use the correct reference/fingerprint for each.
Multiple signers/key rotation need separate review. Matching filenames or package
and version alone does not establish compatibility. This command contacts no
device and uses no private key.

## Separately approved device preservation check

The following is owner-operated, never an automatic test. Both installed and
candidate APKs must remain debuggable for `run-as`; the script rejects a
non-debug candidate before contacting a device. Non-debug releases need a
separately reviewed preservation method.

After approval, park the vehicle, finish monitoring, close OpenWrench normally,
and connect exactly one authorized device. From the repository root:

```sh
bash scripts/verify-inplace-apk-upgrade.sh candidate.apk reference.apk \
  org.openwrench.android.livehomepreview \
  91dcbbea2c6f75af188caea1127754c33e9e14a42a7bf4f5ce9e90c01a07a31e
```

The script checks reference/candidate identity first, then pulls and checks the
installed APK too. It rejects fresh/split installs, ambiguous devices and
downgrades. After the owner types UPDATE, it hashes existing saved scan, drive,
case and diagnosis files, installs only with `adb install -r`, then compares
history before first launch. Empty/unreadable history or any individual hash
failure stops the check. It never uninstalls, clears data, downgrades or launches
the app. Its temporary local copies are removed.

A byte match is not proof of compatibility after opening data. Manually reopen
old diagnoses, scans, drives and cases and verify normal saves. Automated
verification must not start a hardware scan. Unit tests use fake Android tools
and synthetic history; they do not establish real-device upgrade preservation.
