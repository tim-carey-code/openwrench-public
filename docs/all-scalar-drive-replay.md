# Extended scalar saved-drive replay

Saved-drive schema `openwrench.drive-history/7` can retain and replay every scalar
PID in the current shared `ObdDecoder` catalog: 55 scalar channels out of 69 Mode
01 entries. The other 14 entries are structured values and are not accepted as
scalar series. Decoder coverage is not a vehicle capability bitmap. A particular
vehicle may support only a subset; this work provides no verified Subaru bitmap,
manufacturer-specific PID support, or physical adapter qualification.

## Compatibility and isolation

The new reader preserves schema 1–6 validation and migration behavior, including
both historical schema 5 variants. The writer emits schema 6 whenever the drive
fits that format's original 12 scalar types / nine series limits; it emits schema
7 only for extended channel or summary coverage. Old installed apps reject schema
7 as unsupported. No automatic lossy downgrade or silent channel truncation occurs.
Reading/importing does not rewrite source files, save imported drives to history,
connect to an adapter, or produce a new diagnosis.

Only generic `review.series` and overall `review.signals` gain the wider catalog.
Condition aggregates, warning-event samples, assessment fields, monitor input
selection and the nine-series hardware health recorder retain their existing
scope. This does **not** turn live recording into an all-PID recorder. The maximum
24 selected live scalars and the requirement for ECU-advertised support remain.
The separate Civic replay format remains hardware-attributed and is not used for
this synthetic artifact. No UI layout, picker launch/cancellation guard, polling,
module configuration, signing or package identity is changed.

## Schema 7 contract

The keys and row shapes match schema 6. `origin` remains mandatory and is never
inferred from values. Series and overall summaries accept only catalog scalar
PIDs, with no duplicates, canonical names/units, finite values and existing time
bounds. Integer decoder channels require whole nonnegative values within their
native 8-bit (warm-up count) or 16-bit range. Explicit null chart receipts remain
unavailable. Unknown IDs and structured values are rejected.

Limits apply together: 2,000,000 UTF-8 bytes per JSON file, 55 distinct scalar
series/summaries, 1,200 points per series, and 20,000 total retained series points
for schema 7. Oversized input is rejected rather than decimated. The existing ZIP
limits remain 25 MB input/expanded data, 20 drives and 100 entries, with path, ID,
duplicate, UTF-8 and CRC checks. Budget checks precede mapping excessive point
arrays to chart objects; JSON parsing remains bounded by the file-byte limit.

Replay uses only retained offsets, nulls and breaks. It does not interpolate,
manufacture sensor timestamps, animate summary-only data, or fill missing channels.
Integer signals keep integer value/type semantics. The inherited internal
`SignalSupport.SUPPORTED` denotes a decodable retained archive channel in this
route, **not** a capability advertised by an ECU. A separate retained-replay flag
makes picker/inspector support labels and inventory counts say retained replay
channels with ECU capability unverified; support filters classify these channels
as unknown while the channels remain selectable. The existing source area and
inspector identify synthetic/historical data and say Not live. Imported provenance
is declared metadata, not authenticated proof of a capture's origin.

## Synthetic Legacy-inspired fixture

`kotlin/androidApp/src/test/resources/SYNTHETIC-Legacy-inspired-55-channel-5min-drive.json`
is a direct-import schema 7 demonstration for the updated reader. It is not a
measured Subaru recording or a diagnostic reference. It covers all 55 scalar IDs
with 301 timestamps each, from 0 through 300,000 ms (16,555 total points). Forty-seven
channels contain fictional numeric samples; eight remain explicit null-only:

- `0121`, `0130`, `0131`, `014D`, `014E`, `01A6`: unverified history/counters.
- `015B`, `01A5`: hybrid battery and diesel dosing, not applicable to the stated
  non-hybrid gasoline scenario.

The 14 structured PIDs are listed as omitted in the report and `coverage.json`.
They are not replaced with zeros, scalar approximations or fabricated status.
Some numeric channels are intentionally constant rather than animated noise.
All present values are quantized to a code the actual app decoder can represent.
The extended channel set is a generic decoder demonstration, not a claim that
this Subaru exposes all pressure, pedal, throttle, torque or gearing variants.

The warm-engine/level-road scenario follows the earlier verified synthetic file:
idle, gentle acceleration to 50 km/h, cruise, stop, idle, acceleration to 80 km/h,
cruise, stop, idle. All channels share an artificial 1 Hz grid; nulls at 211–212 s
and the recovery break at 213 s exercise missing receipts, not vehicle faults.
Airflow/load/RPM are correlated estimates. Fuel-rate and trim-hold assumptions do
not assert actual closed-loop/fuel-cut status. Assessment is null, ECU is null,
there is no VIN, and UTC dates are explicitly fictional. A pass covers the
300-second retained span plus the existing one-second final hold, with 60-second
rolling chart history.

Reproduce with `python3 scripts/generate-all-scalar-drive.py`. The generator is a
development utility, not an application dependency. It validates the unchanged
seed's SHA-256, reads scalar identities from the actual decoder, and writes both
the JSON and its per-PID coverage manifest. The fixture tests use the actual codec,
JSON/ZIP importer and replay state machine, and round-trip every numeric fixture
value through `ObdDecoder.decode`.

The [official Subaru Canada 2013 Legacy product guide](https://www.subaru.ca/content/7907/media/en-ca/download/2013LEG_PG_EN%20FINAL%20v2.pdf),
page 4, supports only the model context: 3.6L horizontally opposed six and 5EAT.
It is not a source for these generated telemetry readings, PID availability,
exact tire/shift calibration or healthy reference ranges.
