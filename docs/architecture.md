# Native Android architecture

The application runs entirely on Android. `androidApp` renders the Compose UI,
maintains lifecycle/session ownership, handles Android permissions and file
pickers, and opens an explicitly selected Bluetooth Classic SPP or USB serial
adapter. It does not start a server or make HTTP diagnostic requests.

`shared` contains Kotlin diagnostic models, the deterministic simulator, the
Mode 01 decoder catalog and `NativeSession`. The session checks its read-only
command allowlist, discovers advertised capabilities, validates responses and
returns typed readings. Platform transports implement `NativeTransport`; UI code
does not encode or decode vehicle messages.

Hardware, simulator and imported replay data have distinct origins. A failed
hardware operation closes the session and requires explicit reconnect. Failed
cleanup blocks another session until release is confirmed; a non-retryable Bluetooth
close failure remains blocked for the process lifetime. Lifecycle stop ends
polling; foreground return never reconnects silently.

Snapshots use the bounded `openwrench.snapshot/1` JSON format. File access goes
through Android's document picker. Import validates the schema and provenance,
and imported readings remain historical data. VIN export is opt-in. Timed Civic
recordings preserve original timestamps, source age, playback offsets and
provenance without creating a live connection.

The existing dashboard presents pinned cards, signal details and receipt-based
trends. Display pins are independent of acquisition selection. Connect leaves
polling stopped; Start/Stop live data explicitly controls the selected watch.

The [H02A health bridge](vehicle-health-h02a.md) normalizes already decoded
receipts into H01 observations and plans optional health inputs within the same
controller watch. `NativeController` remains the only polling and I/O owner.
[H02B structured acquisition](vehicle-health-h02b.md) allows only
advertised Mode 01 PID `0103` as a health-only structured read, deferred whenever
dashboard signals are selected to protect gauge freshness.
[H03A evaluation hardening](vehicle-health-h03a.md) tightens the existing pure
Kotlin evidence gate without activating any diagnostic rule or changing I/O.
[H03B-2 assessment acquisition](vehicle-health-h03b2.md) adds a bounded,
synthetic-only action and cohort contract in `shared`.
[H03B-3 controller integration](vehicle-health-h03b3.md) adds explicit passive and
focused acquisition APIs to the existing `NativeController` worker. Admission
reserves one operation immediately; focused assessment, VIN, snapshot and live
start cannot queue behind one another. Passive assessment observes only completed
dashboard reads during an already-running watch. Focused acquisition requires
live and its pending read to have stopped, preserves selections/rates, and never
restarts live. The ordinary H02B planner and cadence gate remain in force.

The process-owned controller survives Activity recreation; `onStop` cancels
assessment and releases its session through the same worker. Generation and
instance checks suppress late results, and failed release continues to block
reconnect. Controller receipt times, dashboard age ticks and replay intervals
all use Android `SystemClock.elapsedRealtime()`, including suspend time; test
clocks remain injectable. UTC is used only for existing capture metadata.
No production assessment profile, context mapping or diagnostic rule is registered.

The shared JVM target supports fast Kotlin conformance tests. There is no desktop
application, Python dependency or backend process in this build. Historical
reference source remains in Git commit
`f1d0e3c1dffe701f7efa1b3c7a04a53150e1eee5`; checked-in Kotlin and JSON fixtures
preserve expected results independently of that source.
