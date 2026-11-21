# Start Diagnosis

The default Android screen groups the old record/review/export workflow into a
single locally saved diagnosis. No drive-to-scan or case conversion is required.
The four primary destinations are Diagnosis, Live sensors, History and Advanced.
Advanced retains the individual scan, health, adapter and prior-history tools.

## Using a diagnosis

1. While parked, select and connect a paired Bluetooth Classic SPP or USB ELM327
   adapter. A demo or replay is explicitly labeled and cannot record a hardware
   diagnosis. Connection and permissions remain explicit.
2. Tap **Start Diagnosis**. The existing serial owner stops/drains any ordinary
   live polling, collects a supported standard scan with warning context, then
   starts health monitoring and receipt recording automatically.
3. Keep the app in the foreground while driving. Do not operate controls while
   driving. The screen stays awake during hardware health monitoring.
4. When parked, tap **Finish Diagnosis**. Recording stops, queued receipts are
   saved, and a final scan is attached only to the same process-owned connection.
   The saved diagnosis presents Analysis, Charts and Evidence. A diagnosis
   reopened after restart finishes from saved evidence, unless the user first
   explicitly resumes it on a connected vehicle.
   Completion waits up to ten seconds for the final drive analysis to be saved.
   If storage is still delayed, the result labels its partial checkpoint and
   offers **Reload saved analysis** after storage recovers.
5. **Export diagnosis** opens Android's document picker for one ZIP. On Android
   10 or later, if the picker cannot open, the app saves the ZIP directly to
   Downloads and confirms the destination. No account, cloud upload, network
   runtime or language-model service is used.

**Pause for another drive** saves the segment. Continuing or adding a drive
requires confirming that the connected vehicle is the same vehicle. Adapter
identity, ECU response addresses and connection-generation counters cannot
establish vehicle identity. Each segment has a separate time origin and scan.
Finishing offline preserves existing evidence and states that a final connected
scan was not collected.

Rotating the screen recreates the Activity while the process-owned diagnosis and
adapter session continue. Leaving the foreground, unplugging, a transport
failure or explicit disconnect ends collection and releases the native session.
Reconnection is never automatic.
Failed release continues to block reconnection. The parent diagnosis stays
available in History, including after process death.

## Recorded evidence and interpretation

`readings.csv` contains every retained, completed live Mode 01 receipt admitted
during the diagnosis segment, including `NO_DATA`. Rows include diagnosis and
segment IDs, connection generation, ECU response address where supplied, elapsed
receipt time, wall time anchored to the segment start, PID, value/structured text,
canonical unit and availability. These are sequential adapter receipts, not
synchronized ECU measurements or raw wire frames. Scan measurements and code
services are separate attached reports; they are not fabricated CSV samples.

The report includes recorded scan facts, drive measurements, coverage notes and
the existing versioned offline rules. Interpretations do not confirm a failed
part or whole-vehicle health. Absence of trouble codes is not a healthy verdict.
VIN, adapter addresses and vehicle identity are omitted. The workflow does not
request VIN or add vehicle-write commands.

The ZIP also includes `ai-request.md` and an export-only `ai-evidence.json` file map
for a deliberate [manual AI diagnostic handoff](ai-diagnostic-handoff.md). No
upload or model integration occurs; missing/partial evidence stays explicit.

The ZIP contains `manifest.json`, `report.txt`, `readings.csv`, preserved
`drives/<id>.json` evidence and `charts/<id>.csv` when available.
The report and drive archives are streamed one drive at a time to bound export
memory use. If Android has no available document picker, Android 10 and later
use the system Downloads collection. An incomplete Downloads file is removed
when export fails. Earlier Android versions require a working Files app.

The linked health-summary charts share a cursor and show the existing nine-signal
health profile. To play every retained scalar channel, use Replay in the dashboard
and choose the local saved drive; its PIDs controls support 24 or more concurrent
selections. The saved archive retains all advertised, decodable scalar channels.
Chart traces retain up to 1,200 actual points per signal, 20,000 overall, from the
last 20 minutes. Time buckets adapt to channel count (four seconds at 55 channels);
omission counts are explicit. CSV receipts are not chart-decimated. Missing or
stale readings stay gaps. See [retention and replay limits](saved-scalar-drive-capture.md).
Session-owned drive archives retain analysis/charts even after individual drive
history rotates. Missing linked evidence is explicitly reported.

Operating-condition analysis currently uses the existing fresh moving/stationary
and fuel-loop context. It does not assert acceleration/cruise/deceleration phases.
Oxygen-sensor values may be present in supported parked snapshots, but continuous
O2 charts are not added by this workflow. Manufacturer-specific diagnosis and
cloud AI interpretation remain outside the app's offline, read-only scope. Users
may share the exported packet with a model they choose; its response is neither
a confirmed parts diagnosis nor executed or ingested by OpenWrench.

## Persistence and bounds

`DiagnosisCoordinator` is process-owned alongside `NativeController` and
`DriveHistoryCoordinator`. It orchestrates existing guarded operations. A direct
receipt callback only enqueues data; file operations run off the main thread and
outside the serial transport owner. A disk mutex orders metadata, CSV batches,
archive reads and export. Fresh scan completion tokens prevent stale scans from
being attached to a new diagnosis.

App-private `diagnoses-v1/<UUID>/` stores strict versioned metadata, decoded CSV
and frozen drive archives. Metadata uses replacement files and fsync. CSV batches
are validated before append and rolled back on a failed append. A one-second
flush interval and bounded 2,048-receipt queue limit memory; process death can
lose receipts since the last durable batch. Queue/storage exhaustion stops
recording and reports missing coverage instead of silently dropping evidence.

Each diagnosis has a 20 MiB budget, up to 24 drive segments and 50 attached scans;
metadata is bounded to 1 MiB. History admits up to 50 diagnoses without silently
deleting older ones. This version has no diagnosis-delete UI: reaching capacity
blocks new diagnoses with an explicit error. Legacy drive/scan deletion remains
in Advanced. Export important evidence before clearing Android app data.

Recovery counts durable CSV rows rather than trusting a lagging checkpoint.
An incomplete final CSV row is preserved on disk; the validated prefix can be
exported with an explicit interruption/omission note. That damaged record cannot
resume: export its recoverable evidence and start a new diagnosis. Corrupt
complete rows are not interpreted. Damaged/orphan entries stay visible in the
catalog without preventing access to healthy diagnoses.

## Verification

Scripted tests cover start/finish ordering, scan correlation, foreground
interruption, same-vehicle resume admission, storage failures, process restart,
CSV provenance, quotas, partial-write rollback, archive retention and exports.
The repository's shared/JVM tests, Android host/unit tests, lint and debug build
remain required, along with emulator visual inspection for navigation/layout.
These checks do not qualify a physical adapter or vehicle. No hardware access is
needed by the automated tests.

No runtime dependency was added. Drive-history schema 6 writes explicit origin
and supports the ninth MAF chart series. Readers accept schemas 1–4 and both
schema 5 formats: main's explicit origin and the diagnosis preview's absent
origin. Missing origin becomes UNKNOWN; MAF is never evidence of hardware
provenance. Existing origin, chart gaps, assessments and paired receipts survive
reencoding. Invalid origin values, extra fields and future schemas still fail.

Opening standalone legacy drives does not rewrite them. The existing atomic save path
writes schema 6 when a drive is subsequently saved; embedded case drives and
retained diagnosis drives use the same reader. A diagnosis may refresh its
retained copy from available standalone history using schema 6. Case reports remain frozen and
are not reinterpreted. New app version 7 (0.3.5-native-preview) retains the same
package ID and app-private paths. Do not downgrade, uninstall or clear storage.
The upgrade verifier now hashes diagnosis manifests, CSVs and retained drive
archives as well as standalone scan, case and drive history.

Integration regression coverage exercises both schema 5 layouts, migration on
save, historical case evidence, and reopening the same storage directory before
and after a new automatic scan. The scripted Start/Finish test also verifies two
distinct automatic scan-history entries, hardware drive origin, case creation,
and retained diagnosis charts. Real APK replacement and UI reopening still need
an Android device with the matching signing key; storage tests alone do not
prove that behavior.

Review follow-up: a recreated USB Activity may have no picker selection while
the process still owns a connected adapter. Disconnect and Cancel remain
available from session state; only opening a new connection needs a selection.
Rotation does not refresh, reopen, or release the active transport.

Completed diagnoses release the terminal-metadata reserve when retaining final
drive archives. An archive that exceeds the actual quota is never truncated or
used to delete its older durable checkpoint. The latest standalone evidence is
shown and exported while available, with a visible warning that it could not be
retained and may rotate away. If only a checkpoint remains, both the diagnosis
results and ZIP report explicitly label it partial, with terminal evidence
unavailable. Source changes during ZIP streaming fail export rather than mixing
a final report with older charts. Existing durable receipts and archives remain
intact on replacement failure.

## Navigation

Use **Live sensors** and **History** in the sidebar, or the bottom navigation
on a phone. The empty Diagnosis page does not repeat these destinations as
buttons. The adaptive phone navigation still keeps complete labels visible at
larger text sizes; diagnosis actions and **Set up connection** are unchanged.

## Supported-sensor acquisition

Starting or resuming a diagnostic drive temporarily polls all advertised scalar
PIDs in the native decoder catalog (currently 55), regardless of dashboard
selection. Ordinary Live defaults to the same supported set on connection;
users may reduce that live selection for cadence. Diagnosis does not overwrite
that preference. Stopping diagnosis restores ordinary acquisition selection.
More sensors can mean slower updates; receipt timestamps, stale indicators,
NO DATA backoff and health evidence gates are unchanged. The diagnosis CSV
records completed admitted receipts, not a promise of 55 valid sensor values.
Health assessment and before/after snapshot scope are unchanged; saved scalar telemetry is retained separately from those diagnostic input policies.

## Guided screening foundation (policy 1)

The diagnosis coordinator now exposes `DiagnosisState.guidance` and persists a
per-segment receipt proof. This first slice preserves the existing Start → scan →
record → Finish lifecycle and does not require another drive record. It does not
redesign the Diagnosis page. The progress/check/bank presentation still needs
integration with the independently owned Drive-home screen (#122); the existing
status-message area can display the initial-assessment milestone. No Android
visual acceptance is claimed for that message.

`GuidedDiagnosisPolicy.VERSION = 1` is an app-owned **provisional screening
policy**, not a universal OEM operating-temperature rule or evidence of overall
vehicle health. Its minimum evidence is:

- A completed initial scan with available PID 01 MIL status and an available
  stored-code reply. An empty available code list is valid evidence; NO DATA is
  unknown. Other unavailable scan replies remain explicitly listed in the scan.
- At least one bank with advertised/admitted paired STFT/LTFT and all required
  context, qualified by the existing fuel-correction policy 1 recorder, with the
  additional requirement that fresh coolant remains at least 72°C throughout
  that interval. The existing positive RPM, exact applicable closed-loop,
  independent receipt, pair separation, pair gap, context freshness and variation
  gates are unchanged. This does not infer applicability to diesel or systems
  without this fuel-feedback evidence. Fuel-status fields are not assumed to map
  directly to banks; existing conservative field compatibility is retained.

The 30-second span is a **minimum qualifying duration**, not a completion-time
promise. A prior cold interval plus a later warm sample cannot qualify. A dip
below 72°C, missing/invalid/stale coolant, stopped engine, fuel-state or motion
transition starts a new candidate interval. Already-warm connection requires no
observed cold-to-warm rise and makes no cold-start claim.

The first qualifying proof for each bank is frozen separately. A supported but
silent second bank remains collecting/incomplete; unsupported trim pairs are
unavailable. One qualifying bank can permit an initial assessment while the other
bank's coverage remains incomplete. No bank is labelled passed. Missing scan,
coolant, loop, trims or context leaves an explicit partial assessment. Finishing
capture, including early/offline finish, never means that screening qualified.

The milestone text is:

> Enough information for an initial assessment. More driving may reveal issues
> that haven't appeared yet. Continue driving if you'd like, or finish when parked.

Collection continues in the same segment. The coordinator announces the milestone
once per diagnosis, including explicitly resumed segments with a durable prior
milestone. Fresh proof is required in every new segment; timelines are never
joined. Later evidence can change findings, and the existing drive assessment
continues retaining warning findings after reassuring samples. No unobserved
problem is ruled out. The milestone is an on-screen state/message, not an Android
system notification, audio alert, or instruction to interact while driving.

### Storage and provenance

New guided records use `openwrench.diagnosis/2` within the existing app-private
`diagnoses-v1` directory. Each guided segment stores policy version, initial-scan
availability, evaluation receipt offset, bank status/reason, first qualified
receipt-level fuel interval and milestone offset. Segment identity supplies the
session generation, source ECU and independent monotonic timeline. This records
app-declared hardware origin; it does not authenticate capture provenance.

Original `/1` records remain readable and encode unchanged until an explicit
resume upgrades the parent to `/2`; no historical qualification is invented.
Existing drive/finding/fuel-evidence versions and CSV format remain unchanged.
Current saved-drive ZIP replay imports continue to use the linked drive files.
Older applications that only understand diagnosis `/1` cannot read guided `/2`
metadata; do not downgrade an installation containing these records. Unsupported
future policies and malformed/warm-inconsistent interval proofs are rejected.

Proof is published to metadata only after its consumed receipt batch is durable.
On quota rejection, lost pending receipts cannot supply a milestone; only earlier
durable proof survives. An interruption or restart preserves durable partial
results and never silently resumes acquisition. VIN collection, signing,
transport allowlists and offline-only operation are unchanged. No runtime
Internet, GPT integration, paid API, credentials, server or dependency was added.

### Validation evidence and remaining integration

Local JDK 17 scripted tests cover 71/72°C, warm connection, cold closed-loop,
transient dips, missing/stale/unadmitted inputs, duplicate/cached receipts,
unsupported versus supported-silent banks, engine/fuel/motion transitions, partial
scan, stop/restart, continued collection, once-only resumed milestone, retained
proof, archive validation/legacy compatibility and quota rejection at the first
qualifying batch. Existing coordinator/drive tests cover interrupted/early finish,
failed release, delayed writers and historical replay. None accesses a vehicle.

Offline replay on Tim's Arch desktop used all actual `readings.csv` rows from
three previously supplied archives, excluding the duplicated `part2`. A temporary
local JVM test fed each completed receipt to both original and warm-qualified
recorders; archive identifiers, VINs and raw captures were not committed. Scan
success was supplied as true for this **policy replay only**, not revalidated
from archive scan provenance. Sanitized results:

| Archive | Receipts / PIDs / duration | First coolant | Guided milestone | First qualified bank windows |
| --- | --- | --- | --- | --- |
| A | 9,411 / 35 / 754.200s | 68°C | 94.213s | B2 61.797–94.213s, 10 pairs, min 72°C; B1 81.945–115.732s, 10 pairs, min 73°C |
| B | 6,564 / 15 / 521.921s | 97°C | Incomplete | No interval in either original or warm-qualified recorder; context variation remained an unmet requirement |
| C | 9,115 / 15 / 729.828s | 70°C | 293.705s | B1 263.318–293.705s, 12 pairs; B2 266.830–297.062s, 12 pairs; min 94°C |

Archive A median receipt gaps were 3,603ms coolant, 2,704ms STFT and 3,612ms LTFT.
No timing gates were weakened to obtain these results. Hardware origin is archive
metadata, not authenticated provenance. This is offline receipt-level policy
replay, not Android replay UI acceptance, a new physical test, 55-channel physical
capture, cold-ignition evidence or native hardware qualification.

Remaining work: bank/check/coverage presentation on the accepted Drive-home UI,
exact-APK human visual acceptance, installed-history/signing upgrade checks and
owner-authorized physical acceptance. Keep the implementation PR draft until its
checks and independent review cover its exact latest head and human gates are met.
