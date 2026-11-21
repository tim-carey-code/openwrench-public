# OpenWrench for Android

OpenWrench is an open-source Android diagnostic application written in
Kotlin. Diagnostics, simulator data, snapshots and the Compose interface run on
the device. Bluetooth Classic SPP and USB serial ELM327 adapters connect directly
to Android. Live diagnostics, demo/replay, and saved-drive review work offline, without an
account. This is a sanitized public source snapshot; it is not an app release.

## Our Product Promise

> OpenWrench works with compatible OBD-II adapters and automatically adapts its diagnostic capabilities to the vehicle, adapter, and quality of available data.

## Our Mission — Three Questions, Every Connection

> What's happening with my vehicle right now? Is anything concerning? And what should I check next?

## Start here

- **Without a car:** Explore the dashboard in Demo mode or
  [replay a saved drive](docs/saved-drive-demo.md).
- **With a car:** Use a compatible adapter and follow the
  [Android setup guide](docs/android-native.md). Connect and start a diagnosis
  while parked; finish it when parked. The app groups scans, drive segments,
  assessments, and charts in local history. See the
  [diagnosis workflow and limits](docs/diagnosis-workflow.md).

## Recent changes

- **Guided diagnosis:** Review warning context, readiness, and recorded evidence
  with coverage gaps and suggestions for what to check next. These are not
  confirmed diagnoses. See [diagnostic investigations](docs/diagnostic-investigations.md).
- **Portable evidence:** Diagnosis ZIPs include a report, timestamped readings,
  and, where available, saved drive and chart files. Two new files,
  `ai-request.md` and `ai-evidence.json`, help you review the evidence or
  deliberately share it with a model of your choice. Nothing is uploaded
  automatically. Review exports before sharing. See the
  [AI evidence handoff](docs/ai-diagnostic-handoff.md).
- **Public-build difference:** The in-app hosted AI action is unconfigured in
  this snapshot. Manual export and offline diagnostics still work. Relay source
  is included for developers who choose to configure their own service.

## License and availability

The source in this repository is licensed under the GNU General Public License
version 3 (GPLv3); see [LICENSE](LICENSE). GPLv3 permits charging for distribution
while preserving recipients' license rights. Pricing and distribution plans for
future releases or services are undecided.

## Features

- Paired Bluetooth Classic SPP and supported USB serial ELM327 adapters.
- One diagnosis groups automatic initial/final scans, multiple drive segments,
  offline assessments, interactive charts and an exportable receipt CSV.
- Remembered adapter choices and USB settings, with automatic selection when the remembered adapter has a unique match. Connecting remains explicit. See [adapter selection memory](docs/adapter-selection-memory.md).
- Generic OBD-II supported-PID discovery and 69 standard Mode 01 decoders.
- Up to 24 selected live scalar readings, with RPM and aging readings prioritized on one serial session. Actual update rates depend on adapter latency.
- Ongoing foreground Live Health monitoring that gathers supported context inputs alongside the dashboard, separates moving/stationary and fuel-loop evidence, and updates ECU status, freshness, coverage, and observed trends without a timed capture. See [live health behavior](docs/vehicle-health-live-v1.md).
- A Show fuel trims dashboard preset that reuses ongoing health reads, plus offline review and descriptive comparison of older saved drives without another drive. See [trim visibility and offline review](docs/fuel-trims-and-offline-review.md).
- An explicit parked warning-context scan for stored, pending and permanent code identifiers plus supported freeze-frame data; saved-drive comparison of qualified fuel-correction windows with user-confirmed vehicle identity. See [warning context and comparison limits](docs/diagnostic-context-and-comparisons.md).
- Drive health assessments with evidence, follow-up suggestions and explicit coverage gaps for ECU warnings, readiness, fuel-control states and sustained fuel correction. Findings update during monitoring and remain in saved drives. See [assessment rules and limits](docs/drive-health-assessment-v1.md).
- A factual drive recap, automatic ECU-warning bookmarks with surrounding readings, and linked charts on one receipt timeline for review after driving.
- Durable diagnostic cases combine an explicitly selected structured scan and completed drive, with user-confirmed same-vehicle selection, copied evidence and inspectable limits. See [diagnostic cases and storage compatibility](docs/structured-diagnostic-cases.md).
- Automatic local drive history with checkpoints, interrupted-run recovery, and reopenable recap/charts/bookmarks. The latest 20 drives are retained within 25 MB; see [drive storage](docs/drive-history-v1.md).
- A guided 60-second idle observation and factual Vehicle Health report with timed receipt ranges, same-session code/readiness facts, and explicit coverage gaps. It does not infer a repair or overall healthy state.
- Optional Mode 09 VIN identification, without guessing make, model or year.
- Snapshot capture and bounded JSON import/export through Android's document picker.
- Explicit offline simulator fixtures, historical snapshots and timed recording replay.
- [Loop a saved drive on the dashboard](docs/saved-drive-demo.md), or import a diagnosis ZIP for read-only playback.
- Existing pinned dashboard, signal details and observation trends, with saved pins.
- Read-only command allowlists, operation deadlines and explicit reconnect after errors.

Native hardware support is experimental. Scripted tests and emulator checks do
not qualify a physical adapter or vehicle. DTC clearing, actuator control,
coding, flashing, security access and manufacturer-specific procedures are not
implemented. Supported standard fault-code and frame-zero reads remain read-only.

See [Android setup and behavior](docs/android-native.md) for device permissions,
adapter selection and current limitations.

## Build and test

Install JDK 17, Android SDK platform 35 and build-tools 35.0.0. Open the `kotlin/`
directory in Android Studio, or use the checked-in Gradle wrapper:

```bash
cd kotlin
./gradlew :shared:jvmTest :shared:testAndroidHostTest :shared:assembleAndroidMain \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
```

The debug APK is
`kotlin/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Its application ID is `org.openwrench.android`. Release signing remains
owner-managed. Initial dependency installation needs network access; the
installed application works offline.

## Project layout

- `kotlin/androidApp`: native Compose screens, lifecycle ownership, Bluetooth/USB
  transports and snapshot file handling.
- `kotlin/shared`: portable Kotlin diagnostic models, offline simulator, decoder
  catalog and read-only session engine. The JVM target runs fast conformance
  tests; it is not a desktop application or server.
- `fixtures/v1`: checked-in simulator and snapshot reference data.
- `docs`: architecture, development, safety and Android usage notes.

## About this snapshot

This copy contains current Android source, documentation, and test fixtures
under the existing GPLv3 license. It omits private commit history, internal QA
captures, CI workflows and artifacts, and the hosted relay endpoint. This
snapshot is maintained separately from the development repository.

For contributions and code review, see [AGENTS.md](AGENTS.md) and the
[collaboration guide](docs/collaboration.md). Report reproducible behavior
without posting VINs, private vehicle captures, or credentials.

The retired Python implementation is not part of this snapshot or required
for the build. Checked-in Kotlin/JSON fixtures retain simulator and decoder
reference cases.
