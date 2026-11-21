# Structured diagnostic cases v1

A case keeps **one explicitly selected saved scan and one completed saved drive**
together. In **History & Reports → Cases**, choose **Create diagnostic case**,
select the records, explicitly confirm they came from the same vehicle, review
four evidence sections, then save. Cases can be reopened, copied and deleted.
Evidence sections expand independently; all retained paired drive receipts have
a separate lazy detail view. Existing colors, panels and navigation are reused.

Use **Diagnostics → Scan with warning context** when parked and live polling is
stopped to include the existing pending/permanent-code and frame-zero workflow.
A regular scan still captures Mode 01 and stored codes only. This feature adds
no diagnostic requests. Save the scan explicitly. Historical, simulator and
imported snapshots can also be saved, with their acquisition origin preserved.
To include an explicitly collected VIN in a saved scan, read VIN **before** that
scan and select the acquisition-VIN checkbox. New cases always omit scan VIN.
The current connection's VIN display is separate from acquisition metadata.

## Acquisition provenance

`ScanCapture` is a portable typed model (`schemaVersion = 1`). A successful scan
publication freezes its unique capture UUID, origin, connection UUID, observed
ECU response address, eligible PIDs, Mode 01 values and their sources, separate
code-service results and optional frame-zero result. Service absence means
**not captured**. Available empty, NO DATA, unsupported and unavailable remain
distinct. Per-service notes, frame support, trigger, typed values, sources and
per-PID unavailable statuses are retained. MIL/readiness remain typed Mode 01
fields, not reconstructed report data.

Hardware capture metadata records operation start/completion wall times and
monotonic duration. The connection UUID is an app connection reference, **not a
vehicle identity**; process-local generation is never used to match vehicles.
Save time is separate. A later reconnect or VIN read cannot change this envelope
or attach the new connection's metadata when the earlier scan is saved.
Imported/simulator acquisition times remain unknown; local publication time is
labeled separately. No per-reading measurement timestamps are invented. The
original ECU freeze-frame capture time is always unknown, and retrieval time
never becomes a fault-event time.

## Storage and compatibility

- `scan-history-v1.json` now writes `openwrench.scan-history/2`, with nullable
  typed `capture` per saved record. It reads schema 1 exactly, retaining report
  text and optional VIN. Legacy scans stay readable/copyable and visibly lack
  structured evidence. They cannot create cases. No prose is parsed into data.
  Limits remain 50 scans, 2,000,000 UTF-8 bytes and 60,000 report characters.
  A successful explicit mutation migrates the complete file atomically; reads
  alone do not migrate it. The filename is retained to avoid competing stores.
- Drive archives now write `openwrench.drive-history/5`, adding an explicit
  `origin`. Native hardware monitoring writes `HARDWARE`; synthetic test records
  use `SYNTHETIC`. Schemas 1–4 remain readable; their unrecorded origin is
  `UNKNOWN`, never inferred from an ECU address. Assessment pack versions,
  paired-evidence policy versions and retained findings remain unchanged.
  Existing per-drive/retention limits are unchanged (20 drives within 25 MB).
- `diagnostic-cases-v1.json` uses `openwrench.diagnostic-cases/1`. Each case embeds
  a validated scan-schema-2 record without VIN and a drive-schema-5 archive,
  with original IDs, acquisition/save metadata, full typed evidence and original
  assessment/policy versions. The case also stores its own UUID/save time,
  explicit same-vehicle confirmation, report version 1 and four frozen report
  sections. Reopening never reevaluates a drive or regenerates its report.
  Limits: 10 cases, 25,000,000 UTF-8 bytes, at most 1,000,000 characters per
  report section. Cases do not rotate automatically. Full drive evidence is
  retained; clipboard reports show bounded first/last receipt excerpts, while
  the expandable detail view exposes all saved pairs.

All mutations validate typed content, write a temporary file, flush and sync it,
then atomically rename over the destination. Size/count limits, exact keys,
versions, UUIDs, service uniqueness, status/value consistency, PID allowlists,
finite values, structured PID fields and source address syntax are checked.
Frame readings have their own allowlist, including Bank 2 PID 08/09; the public
snapshot interchange allowlist is unchanged. The existing drive codec also
validates all retained intervals, receipts, bounds and policy versions. JSON
checks reject duplicate keys and excessive nesting before tree construction.
Unknown, oversized or corrupt stores surface an error and block mutation; they
are never replaced with an empty history. There is no new dependency or network
access. Files remain app-private and are removed when app data is cleared.

A case is an immutable evidence copy. Source deletion, source drive rotation,
subsequent monitoring and application restart do not change it. Deleting a case
removes only that case and its copies, leaving original history unchanged.
Case deletion is logical removal from the app's store, not secure media erasure.

## Selection, lifecycle and interpretation

Process-owned coordinators admit one persistence operation at a time and keep
admitted atomic mutations alive across Activity recreation. Selection passes
fixed record IDs and confirmation into preparation; changes reset confirmation.
Preparation rereads those exact records and requires a structured scan and a
completed drive. Missing/rotated records fail explicitly, never substitute a
new record. The preview owns frozen copies. Cancel invalidates pending preview
or open deliveries; a save consumes only its matching preview ID. Save/delete
admission is serialized, with no background upsert or retry that could recreate
a deleted case. An admitted save finishes even if its UI is closed, but canceled
UI deliveries do not reopen it.

The four report sections show scan results, frame-zero conditions, the drive's
original findings/qualified intervals, and interpretation limits/next checks.
Next checks come from explicit frame/qualified-drive evidence gaps and the
original findings' documented next steps. They add no mechanical thresholds,
fault ranking, component recommendations or probability estimates.

The established relationship is **the user's same-vehicle selection**. Matching
addresses do not prove vehicle identity, the same physical ECU or the same
fault. Missing, different, imported, synthetic or unrecorded origins leave
observations separately attributed. Even matching hardware response addresses
cannot prove the same event. Frame-zero trigger association is specific to its
returned trigger, not every code or any drive bookmark. Historical evidence is
not current condition; fuel patterns are not causal diagnoses; record differences
are not repair evidence. Missing intervals are never rebuilt from charts.

Hardware acquisition, allowlisted requests, capability gates, cancellation,
CAN header restoration and failed-release ownership remain unchanged. No
physical adapter was discovered or connected during development or review.

## Reproducing synthetic visual review

`DiagnosticCaseTest.generatesClearlyLabeledEmulatorFixturesWithoutAnyPhysicalAdapter`
writes fixture archives under `kotlin/androidApp/build/diagnostic-case-fixtures/`.
These include an imported-simulator scan with missing context, a legacy scan, a
synthetic completed drive with pack-3/policy-1 paired evidence, and a populated
synthetic case with Bank 2 frame values and a missing coolant reading.

Install the debug APK into an isolated emulator. With the app stopped, copy the
scan/case files into its `files/` directory using `adb shell run-as
org.openwrench.android`, and the UUID-named drive file into
`files/drive-history-v1/`. Do not seed production data or a physical device.
Exercise Cases selection, disabled legacy selection, confirmation, review,
save, copy, delete, process restart/reopen, source limitations and expanded
missing/populated evidence. Repeat with Android font scale 1.5. UI screenshots
and verification results live in `docs/evidence/structured-diagnostic-cases/`.

## Evolving investigations

The separate [offline investigation foundation](diagnostic-investigations.md)
can retain case copies as evidence for an append-only complaint → competing
hypotheses → distinguishing question → recorded result → revision trail.
The [offline workspace](investigation-workspace.md) is available from the Cases
list through **Investigation workspace**. Starting an investigation is explicit.
Case reports and original assessments remain frozen; opening a case does not
create or update an investigation.
