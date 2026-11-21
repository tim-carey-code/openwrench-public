# Guided idle observation and factual Vehicle Health report

The Vehicle Health workspace offers a user-initiated 60-second idle check on a
connected hardware adapter. The user supplies the operating condition (parked,
engine idling); the app cannot verify it. Live polling must be stopped. The check
requires the ignition and adapter to remain connected through the final scan. It
uses the existing single `NativeSession` owner and reads only advertised standard
Mode 01 scalar PIDs from this fixed set: RPM (`010C`), speed (`010D`), coolant
temperature (`0105`), short- and long-term Bank 1 fuel trims (`0106`, `0107`),
and control-module voltage (`0142`). Unsupported inputs stay visible as coverage
gaps. RPM takes every other read slot when supported; the remaining inputs
rotate through the other slots. Each read is serialized and separated by at
least 500 ms. The capture retains at most 160 actual receipts with monotonic
receipt times; it does not
invent ECU sampling timestamps or turn sequential values into a simultaneous
snapshot.

After the 60-second window, the same session runs one existing capability-gated
Mode 01 diagnostic sweep followed by the existing read-only Mode 03 stored-code
request. This is a final scan, not a repeated 33-PID sweep throughout the idle
window. The immutable report combines that same-session scan's MIL, ECU-reported
code count, stored-code identifiers and readiness fields with the timed range of
each targeted scalar signal. It labels unavailable and `NO DATA` evidence
explicitly. A failed scan leaves the timed capture as a partial report with scan
facts unknown; the native session follows its existing fault and cleanup policy.
The report stores separate wall-clock start and completion times and a monotonic
60-second capture duration; a slow final scan does not get folded into that
capture duration. Cancelling during timed capture keeps the adapter connected
and retains partial timed evidence. Cancelling during the final scan interrupts
between native reads, requests adapter release, and retains timed evidence with
scan facts unknown; reconnecting is required after cleanup is confirmed.
The report never imports scan facts from a previous connection. A completed
report is labeled historical after disconnect or reconnect. VIN is never
requested by this operation or included in its copied text.

The report leads with direct MIL, code, and readiness status, then counts how
many advertised target signals returned values and how many reads returned
`NO DATA`, before showing each timed signal's first/last and observed range.
Its copied text is a compact summary with first/last receipt times. It does not
infer a failed part from a fuel trim, assess driving conditions, use manufacturer-specific
limits, clear codes, or call a vehicle healthy. An incomplete readiness monitor
is shown as an ECU-reported state, not as a fault-cause verdict. The existing
`HealthEvaluator` remains the gate for later reviewed diagnostic rule packs;
this first report does not activate a rule pack or a health score.

The report is retained in memory through the current app process and can be
copied by the user. Earlier local scan history stores plain-text scans only; it
is not treated as fresh typed evidence for this report. Automated tests use
scripted transports and virtual time. Physical 60-second idle and post-capture
scan qualification remains necessary.
