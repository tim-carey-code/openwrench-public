# H03B-4: read-only diagnostic scan delivery

The Diagnostics workspace now exposes a manual hardware scan. After the user
stops live polling, the existing single `NativeSession` owner reads each PID
that is both advertised by the selected ECU and present in the 41-PID Mode 01
snapshot allowlist. The dashboard's 24-signal live selection does not restrict
the scan. The app sends these requests sequentially; it does not add another
serial connection or poll them alongside the live dashboard.

The scan presents every returned value and each exact `NO DATA` response. When
PID 01 is available, it displays the ECU-reported MIL state and DTC count, plus
the monitor fields in the parameter list. Those are reported facts, not a
diagnosis. The app does not currently read DTC identifiers, interpret sensor
values against vehicle-specific limits, or claim that a vehicle is healthy.
H03C can add reviewed read-only DTC retrieval and diagnostic rules separately.

The scan result is tagged with its session generation and completion clock.
After a disconnect it is labelled historical and remains visible while the next
adapter connection opens. A new scan replaces the previous result only when every
requested read succeeds; a protocol or transport failure does not publish a
partial scan. Each exchange remains bounded by the native session's 3-second
timeout and response-size limits; the full sequential sweep can take longer
than a dashboard refresh. VIN collection is still a separate explicit action.
A VIN is attached only to a scan from the same session and is omitted from
exports unless selected; reconnecting cannot attach a new vehicle's VIN to
an earlier scan.

Offline simulator fixtures and imported snapshots have distinct source labels;
only scans captured from an ended hardware session are called historical.

Scripted transport and presentation tests cover capability filtering, stopped
live admission, reconnect preservation, source labels, MIL/count extraction,
and `NO DATA` reporting. A physical adapter and vehicle have not been exercised by
this change.
