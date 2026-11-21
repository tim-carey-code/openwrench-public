# Ongoing foreground vehicle health evidence

Vehicle Health opens on Live monitoring. Starting health monitoring joins the
existing live reader or starts live acquisition on the connected hardware
session. No timed capture is required. The optional guided idle check remains
available on its own tab. Start monitoring before driving; the view then updates
without requiring interactions while the vehicle is moving.

## Acquisition

The monitor reuses dashboard requests and adds advertised standard Mode 01
inputs in this order: RPM, speed, coolant, Bank 1 short/long-term fuel trims,
module voltage, calculated load, throttle, Bank 2 short/long-term trims, mass
airflow, and manifold pressure. Dashboard selections and their rates are
preserved. The combined scalar plan has 24 slots. Unsupported or capacity-limited
inputs remain visible as coverage gaps; the monitor does not evict user choices.

Structured fuel-system status (`0103`) and monitor status (`0101`) have separate
typed requests on that same serialized owner. Fuel status is requested at the
low live rate, and monitor status every 30 seconds. These are requested start
intervals, not throughput guarantees. The existing timeout, framing, selected
ECU, NO DATA backoff, RPM priority, and five-second dashboard expiry remain in
force. Mode 03 code scans, VIN queries, and full diagnostic sweeps are not run
repeatedly by monitoring. A monitor-status code count is not a fresh list of
stored-code identifiers.

Actual scalar monitoring receipts also feed pinned dashboard cards. The Show
fuel trims action can promote already-acquired trims without changing their
request rates; promoted trims remain selected after monitoring ends. See
[fuel trims and offline review](fuel-trims-and-offline-review.md).

Stopping health monitoring removes its extra requests and leaves ordinary live
dashboard acquisition running. Stopping live data also stops monitoring. Faults,
disconnect, and lifecycle stop terminate current hardware evidence through the
existing owner and cleanup policy. The active hardware monitor keeps its app
view's screen awake and restores the prior screen setting when monitoring ends.
This version is foreground-only: leaving the app or manually locking the screen
releases the adapter. Reconnection requires an explicit start and does
not reuse an earlier session's evidence as current data.

## Operating conditions and evidence

Fresh speed distinguishes moving from stationary observations. Stationary
engine-running/stopped labels additionally require fresh RPM. Neither label
establishes Park, idle load, or a fully warmed engine. Fuel-system status retains
both reported system states, including open-loop reasons. Missing or stale
context remains unknown. Motion and fuel-loop transitions start a new current
condition window, so their measurement ranges do not mix different conditions.

Current signal summaries retain up to 120 seconds and 64 actual receipts per
signal. Signal latest values expire after 15 seconds, motion/fuel context after
10 seconds, and low-rate ECU status after 45 seconds. These are evidence-age
policies, not mechanical operating limits. Clock ticks update age without
inventing samples. NO DATA breaks the affected input's current measurement run.

The session retains at most 16 context aggregates with counts, min/max, and
first-to-last receipt spans. Spans may include gaps and are not continuous
driving durations. Earlier aggregates remain explicitly historical evidence,
never support for a current finding. Reports are user-copyable and automatically
saved to [local drive history](drive-history-v1.md); they contain no VIN or raw
adapter frames.

The live view reports ECU warning/readiness states, supported coverage, and
observed measurement ranges during stationary and moving operation. The
[drive assessment](drive-health-assessment-v1.md) interprets qualified evidence
for ECU warnings, readiness, fuel-control states and sustained learned fuel
correction. It does not activate manufacturer-specific thresholds or a
whole-vehicle grade. Its bounded recorder qualifies exact receipts and operating
context before producing a finding; saved charts are not diagnostic inputs.

Stopped reports keep live fields unavailable, but present a separate last-recorded
context and ECU summary with receipt offsets and age at the frozen end point.
The export omits empty current-value rows after monitoring ends and does not
report context missing merely because the run stopped. A latest NO DATA status
attempt is not replaced by an older valid MIL/readiness value; an old status
receipt is labeled stale at the end rather than treated as current. An empty
bookmark list does not establish that the vehicle is healthy. Clipboard exports carry the
`live-health/2` report-format marker and, when available, the local drive-save
status. This text version does not change the archive JSON schema.

## Review after a drive

A drive recap summarizes actual signal counts, first/last readings, observed
ranges, and stationary/moving context evidence. Highlights describe observed
changes and missing evidence rather than infer failed parts from an arbitrary
range. Monitoring elapsed time is not automatically continuous driving time;
context spans can include gaps. Finishing or disconnecting leaves this review
available in History & Reports. Starting another monitor creates a new review instance,
even when the adapter connection has not changed.

Linked charts share the same elapsed-time axis for RPM, speed, calculated load,
Bank 1 fuel trims, and coolant. Retention is bounded to 20 minutes and 1,200
points per signal, using actual timestamped retained receipts. The common
cursor shows each signal's nearest retained receipt and its time offset, not a
simultaneous ECU snapshot. NO DATA and acquisition gaps break lines. Chart
sampling and retention are stated in the UI; the line is not a guarantee that
every intermediate change was recorded.

Warning bookmarks retain actual readings from up to 15 seconds before and
15 seconds after the ECU warning was first observed. MIL activation and an
increased ECU-reported DTC count can trigger a bookmark. A warning already
present on the first observation is labeled accordingly. Unknown or stale
status does not prove a new transition, clear a prior warning, or repeatedly
bookmark the same warning. A fresh clear status can rearm transition detection.
The event time is a receipt time, not the moment a physical fault began.

At most 12 bookmarks and 512 receipts per bookmark are retained. Truncation and
incomplete pre/post windows are explicit. Stop, disconnect, or lifecycle end
freezes any unfinished post-event window as partial; evidence from a later
monitor or connection cannot complete it. No bookmark is a component diagnosis,
and no bookmarks does not establish that the vehicle is healthy. Bookmarks,
recap, and charts share the existing reader and introduce no extra OBD requests.
Event charts use isolated retained points when sample limits prevent continuity
checks, so a discarded NO DATA marker cannot become a connected line. Copied
reports include up to 64 actual receipts spread across each saved event window,
with the full retained window available for review in the app; the text preview
is bounded for Android clipboard transfer.

For the tester flow, connect while parked and open Vehicle Health → Start health
monitoring. Keep the app foreground during the drive. Once parked, choose Finish
drive report and review Recap, Charts, and Events. Check the automatic save status;
the saved report, charts, and bookmarks can be reopened later from History &
Reports → Drives. Existing saved diagnostic scan history remains under Scans.

## Broader module coverage

This increment collects standard OBD evidence from the selected response source.
ABS, airbags, body modules, and a dedicated transmission-module assessment are
not covered. Generic OBD replies must not be used to label those modules healthy.

Broader support is a separate enhanced-diagnostics increment: establish verified
vehicle-family and adapter capabilities, identify reachable modules, then add
documented, allowlisted read-only status and DTC requests with per-module
provenance. Begin with one supported vehicle family and test transcripts before
claiming broader coverage. Module absence, unreachable networks, and unsupported
protocols must remain distinct from a completed no-code response. Manufacturer
data, module routing, and adapter network support vary; see the adapter vendor's
[standard/enhanced diagnostics distinction](https://support.obdlink.com/support/solutions/articles/43000713278).

## Qualification

Automated transports and virtual clocks must cover single-owner acquisition,
driving/stationary and fuel-loop changes, unsupported/full-capacity plans,
missing/stale evidence, monitor stop, lifecycle/disconnect, and bounded long-run
memory. UI inspection includes a populated synthetic driving view and an empty
hardware view. These checks do not qualify physical adapter throughput. On-car
qualification should start monitoring while parked, collect ordinary driving
and stationary evidence with the app foreground, and inspect/copy the resulting
report after stopping safely.
