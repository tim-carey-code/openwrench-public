# H02A health observation bridge and input planner

H02A adds an opt-in bridge between the existing native Android acquisition owner
and the H01 health contracts. No health rule or diagnosis is installed. The
dashboard still selects, starts, stops and displays the same readings. No
vehicle request was added; all live reads remain `NativeSession.read` calls from
`NativeController`'s single worker and its existing Mode 01 scalar allowlist.

## Interfaces

- `HealthObservationBridge.normalize` converts one already decoded
  `NativeReading` into an H01 `Observation`. It reuses `nativeSignalDefinition`,
  `ObdDecoder.catalog` canonical units and the decoder's typed value. PID and
  module are retained; the current unaddressed ELM responses do not identify
  an ECU, so live module is null. The supplied monotonic time is the app's
  completed-read receipt, not an ECU sample timestamp. A source timestamp is
  carried only when a caller actually supplies one.
- `HealthInputPlanner.plan` takes dashboard PID/rate selections, ordered H01
  `InputRequirement`s and current advertised PIDs. It produces a dashboard map,
  a combined acquisition map and one explicit decision per requirement. The
  dashboard map takes priority. Compatible requirements share one PID request;
  new health PIDs use free slots within the existing 12-scalar limit. Unknown,
  non-scalar, disallowed, unadvertised, capacity-blocked and cadence-blocked
  requirements are reported. A health-only PID is currently rejected while any
  dashboard PID is selected: two sequential bounded 3-second reads plus the
  200 ms inter-read delay can exceed the existing 5-second gauge freshness
  limit. A preceding fast response cannot prove the next response will be fast.
  The same PID can still be shared with the dashboard, and health-only polling
  can run when the dashboard selection is empty. Additional concurrent signals
  need a separately reviewed shorter non-fatal read bound or another mechanism
  that can prove dashboard freshness under worst-case adapter latency.
  Requirement sample/window/age bounds are evidence criteria, not polling
  instructions. The default additional rate is Normal (2 seconds); actual
  completed-read spacing is reported separately.
- `NativeController.setHealthRequirements` applies requirements only while the
  controller is idle and cleanup is confirmed. It returns an explicit update
  status and per-requirement plan decisions even when the update is refused.
  Disconnected requests are stored for a future explicit connection, marked
  `DEFERRED_DISCONNECTED` and `NOT_ADVERTISED`, and replanned only after that
  session discovers its own advertised capabilities. Disconnect and fault
  release clear the prior vehicle's support and admission.
  It never starts acquisition. `NativeUiState.watched` remains the dashboard selection;
  the sole poll loop reads `healthPlan.acquisition`. Health-only results do not
  enter dashboard readings. `healthObservations` stores a bounded receipt window
  for each signal; `healthCadenceMillis` and `healthInputFreshness(now)` expose
  observed spacing and latest receipt age. Neither is an ECU sample rate.

No requirements are registered in H02A, so its default acquisition plan is
identical to the current dashboard watch. Stop, disconnect and read errors
create non-available receipts and clear prior available evidence for each
affected signal. A later available receipt starts a new evidence window.
Start clears earlier windows; a new connection gets a new generation. Late reads
after cancellation or stop cannot publish observations.

Simulator observations use `SIMULATOR`; imported snapshots use `REPLAY`, even
when the saved source was simulated. The separate `replaySimulated` flag retains
that original provenance. Snapshot import has no trusted capture or ECU time,
so its observation receipt is the local import time and `sourceTimestamp` is
null. A replay observation is never presented as current hardware evidence.

## H02B and safety limits

H02B adds a separately gated structured PID 0103 path; see
[structured diagnostic context acquisition](vehicle-health-h02b.md). Current
unaddressed responses cannot establish which ECU produced a value. Future rule integration must
obtain reviewed applicability/provenance, select only admitted inputs, and
evaluate using the measured receipts with explicit freshness and source checks.
Physical adapter and vehicle behavior remains unqualified; scripted tests do
not establish native hardware safety or cadence under load.
