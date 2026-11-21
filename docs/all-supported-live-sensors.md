# All supported live numeric sensors

## Selection and safety

On each successful hardware connection, the default watch contains every
numeric Mode 01 PID that the selected ECU advertises and the native decoder
allowlists. The former nine-sensor default and arbitrary 24-sensor ceiling no
longer truncate this watch. The finite scalar decoder catalog bounds the
selection (55 PIDs in this source version). Unknown, unadvertised, structured,
and manufacturer-specific PIDs are not admitted as live numeric sensors.

Connecting does not start polling. Start remains explicit, selection changes
require a stopped watch, and users can deselect sensors to improve cadence.
Pinning or changing display modules does not change acquisition. Structured
status data keep their existing diagnostic-scan and optional Vehicle Health
paths. No additional diagnostic mode, ECU source, raw-command path, VIN request,
or write command is introduced.

## Throughput and bounded scheduling

The existing single owner performs one bounded adapter exchange at a time,
retains the inter-read gap, prioritizes RPM, and ages all other due selections
so they continue receiving service. Repeated NO DATA responses back off to a
maximum ten-second retry delay; a valid response restores the requested rate.

When Vehicle Health is enabled, speed and fuel-system status receive extra
best-effort priority after five seconds without a receipt. ECU monitor status
receives priority after 35 seconds, while retaining its 30-second requested
interval. Priority never overrides retry backoff. These priority reads alternate
with ordinary non-RPM service so slow or absent context cannot starve the rest
of a full watch.

A larger watch cannot make an adapter faster. In particular, all 55 sensors
cannot remain within the dashboard's five-second freshness limit on many
adapters. Receipt ages and measured cadence remain real; expired values remain
stale, charts retain gaps, and insufficient health context remains unknown.
No catch-up burst, fabricated refresh, or freshness guarantee is introduced.

## Diagnostic drives

Start Diagnosis and Add Drive explicitly acquire every advertised, allowlisted
scalar PID, even if the ordinary live dashboard selection was reduced or empty.
This temporary acquisition policy does not change that selection; pause, finish,
interruption and disconnect remove it. Resume applies the policy again against
the current connection's discovered capabilities. The same single reader and
bounded scheduling/backoff rules apply. Structured health inputs retain their
separate existing path. A vehicle with supported scalars but no health inputs
can still record evidence; missing health context remains unknown.

The diagnosis `readings.csv` retains completed admitted receipts, including
NO DATA and scalars outside the nine health chart series. A larger acquisition
set does not make new diagnostic claims: the existing evidence-qualified health
analysis remain unchanged. Saved scalar charts use the separate bounded telemetry profile described below. Scan snapshots retain their existing
41-PID policy. No value is synthesized for a PID that has not returned data.

## Preserved recorded evidence

Saved snapshots and old recordings are unchanged. Selecting more live sensors
does not add missing signals to an old recording. New saved drives retain every
advertised scalar channel independently of visible graph selection, using the
existing schema 7 and explicit bounded chart retention. Health-analysis inputs
and warning windows remain separate. The diagnostic receipt CSV still retains
completed admitted receipts independently of chart decimation. See
[saved scalar drive capture](saved-scalar-drive-capture.md) for exact limits,
compatibility and the scrollable 24-plus-channel playback contract.

## Regression coverage

Tests cover full-catalog selection beyond 24 sensors, capability and decoder
gates, deterministic defaults, single-reader fairness at 80/700/3000 ms reply
times, NO DATA backoff and recovery, full-watch health context at 200 ms,
health priority under timeout-speed overload, fuel-trim presets, start/stop,
and source/presentation counts. Existing 12- and 24-sensor timing tests remain.
All transports in these tests are synthetic. They do not qualify physical
adapters, vehicles, or on-device performance.

Diagnostic integration tests cover reduced dashboard selection, all 55 scalars,
pause/resume, restoration of ordinary selection, high-PID export/reload, NO DATA,
unknown-PID exclusion and a scalar-only vehicle without usable health context.
