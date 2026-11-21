# Native Android Kotlin project

This Gradle build contains two modules:

- `androidApp`: the Android application, Compose interface, direct Bluetooth
  Classic SPP/USB serial connections, lifecycle control, snapshot documents and timed replay.
- `shared`: portable Kotlin models, simulator, OBD-II decoders and diagnostic
  session engine. It builds for Android and JVM; JVM is a test target only.

There is no backend service, Python runtime, HTTP client or desktop application.
The installed application operates offline and has no Internet permission.

## Toolchain

Use JDK 17 and the checked-in Gradle wrapper. Android compilation requires SDK
platform 35 and build-tools 35.0.0. Set `ANDROID_HOME` or configure `sdk.dir` in an
untracked `local.properties`. Versions are pinned in `gradle/libs.versions.toml`
and `gradle/wrapper/gradle-wrapper.properties`.

```bash
./gradlew :shared:jvmTest :shared:testAndroidHostTest :shared:assembleAndroidMain \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
```

Install `androidApp/build/outputs/apk/debug/androidApp-debug.apk` on Android 7.0
(API 24) or newer. The package ID is `org.openwrench.android`. Debug signing is
for development; release signing is configured by the owner.

Tests use scripted transports and checked-in fixtures. They must not discover
or connect to real adapters. Physical hardware qualification is a separate,
explicit owner-operated activity.

## Dependencies

- Kotlin Multiplatform shares platform-independent models and protocol logic
  between the Android artifact and JVM tests.
- Jetpack Compose and Activity Compose render the existing OpenWrench interface.
- Kotlin coroutines serialize diagnostic work and publish UI state.
- `kotlinx.serialization.json` validates bounded snapshot/replay JSON documents and
  reads JSON fixtures in tests.
- `usb-serial-for-android` 3.11.0 supplies Android USB serial drivers for CDC ACM,
  FTDI, CP210x, CH34x and PL2303 adapters. Only that dependency group uses JitPack.

## Conformance data

The historical behavior reference is Git commit
`f1d0e3c1dffe701f7efa1b3c7a04a53150e1eee5`. Its Python source is retained in Git
history only. Native decoder tests consume the checked-in
`shared/src/commonTest/kotlin/org/openwrench/nativecore/ObdReferenceFixtures.kt`,
which records the reference source hash. Simulator tests consume
`../fixtures/v1/simulator.json`; snapshot validation also consumes the checked-in
Android `native-snapshot-reference.json`. No reference generator or Python
process runs during tests, packaging or CI.

Current dashboard, replay and contract-only RPM conformance work from main
`96d8e9a7a666f9d5f8fef40c6ba6aa630daca887` is preserved in the native project.

Read [the native Android guide](../docs/android-native.md) for permissions,
supported connection modes, snapshots and known limitations.

The shared [H01 Vehicle Health contracts](../docs/vehicle-health-h01.md) are
connected to native decoded receipts by the [H02A observation bridge and input
planner](../docs/vehicle-health-h02a.md). No health rules or health UI are active.
