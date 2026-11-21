# Native live polling

The dashboard watch selects every advertised, allowlisted numeric Mode 01 PID
on a new connection over one serialized ELM connection. There is no nine-sensor
default or arbitrary 24-sensor cutoff. The finite scalar decoder catalog bounds
selection (55 PIDs in this source version). RPM, speed, load, throttle, coolant,
intake temperature, mass airflow, manifold pressure and module voltage retain
their familiar initial display ordering; all other supported numeric sensors
are also selected. Start remains explicit. Users may deselect sensors while
stopped. The scrollable plot selection is display-only and independent of
acquisition. Structured/status data retain their separate scan/health paths.
See [all supported live sensors](all-supported-live-sensors.md) for scope and
saved-recording limitations.

## Scheduling and transport

The requested start-to-start intervals are:

| Priority | Interval | Signals |
| --- | --- | --- |
| High | 200 ms | RPM |
| Normal | 500 ms | Load, speed, throttle, airflow, manifold/fuel pressure, timing advance, short-term fuel trims and other fast load/throttle values |
| Low | 2 s | Temperatures, voltage, fuel level, long-term trims, runtime, distance and other slower scalar readings |

The scheduler counts response time toward the requested interval. It chooses the
most overdue eligible signal relative to its interval, preserving priority share
without permanently excluding slower inputs under overload. It does not queue
missed polling slots or issue concurrent requests. Every completed reading is
published immediately; it does not wait for the full selection to finish.

Between complete Mode 01 scalar/structured replies, USB and Bluetooth require
a 20 ms completion-to-write gap, replacing the old 200 ms gap. The controller's
20 ms yield overlaps that gap and permits Stop/lifecycle events between reads.
The fast path requires the previous complete `>` prompt. Initialization,
capability discovery, VIN and failed/incomplete exchanges retain the 200 ms gap.
No new ELM commands, multi-PID packets, response-count suffixes, unsolicited
retries or ECU selection changes are introduced. Requests remain allowlisted,
capability-gated, bounded and serialized through the existing session owner.

A PID returning `NO DATA` stays unavailable and is next eligible after 1, 2, 4,
8, then 10 seconds after successive absent replies. Later probes stay at ten
seconds until a valid reply restores the requested interval. Start live data
resets this schedule. Transport/protocol failures still fault the session; this
backoff applies only to successfully decoded absence.

Actual throughput depends on response latency and selection size. The requested
rates are not guarantees. Five-second dashboard expiry is unchanged: late,
missing and stale values remain gaps, and clock ticks never invent samples.
The presenter samples the monotonic receipt clock for each new state. Its
250 ms timer only triggers aging when readings stop changing; it is not used
as a cached timestamp for newer receipts. This prevents fresh readings arriving
between timer ticks from being marked stale or creating false graph gaps.
Genuinely incompatible future timestamps still remain unavailable.
Existing focused assessment worst-case admission keeps its conservative 200 ms
pacing allowance; live performance measurements do not authorize additional
health inputs or alter evidence freshness requirements.

The optional [Live Health monitor](vehicle-health-live-v1.md) is an explicit
combined acquisition mode. It can use free scalar slots and low-rate structured
monitor/fuel-status requests on the same owner. The older generic assessment
planner remains conservative. Monitoring shows unsupported, capacity-limited,
and stale inputs explicitly; it does not guarantee every requested cadence on
a slow adapter.

Passive assessment retention still holds at most four actual receipts per input.
For mixed-rate streams it keeps fresh endpoints and drops the most crowded
interior point, so rapid RPM updates do not erase the whole evidence span while
waiting for a slower input. The existing exact cohort selector, age/skew checks,
NO DATA invalidation and command-free passive ownership remain in force. Focused
collection retains its existing latest-four policy.
Like any four-receipt sample, this can omit a possible cohort under a very narrow
skew limit; it never relaxes qualification to compensate for discarded evidence.

## Timing evidence

Copy latest USB diagnostic includes a bounded summary per allowlisted Mode 01
command: completed prompt-terminated exchanges, mean/max exchange duration and
mean completion interval across the connection. This survives the 180-line
event-buffer rollover. Completion counts may include `NO DATA` and malformed
payloads later rejected by the session; they are not counts of valid samples.
Intervals include pauses between live runs. No response payloads, VINs or device
serial numbers enter the summary. Starting another connection clears it.

The Session button now copies a combined session log. Its live section includes
one event per recent Mode 01 read with PID, decoded scalar value or `NO_DATA`,
elapsed time, and a per-PID summary of counts, mean/max read time, receipt
intervals, latest value and age. The last 320 live events are retained in
memory; aggregate counts cover the whole latest live run. It records only
allowlisted Mode 01 PIDs, never the VIN command. A newer connection or live
run resets the relevant history. Stop and disconnect keep the log available
until a new run or process exit. The USB section still contains adapter
negotiation and wire-exchange timing without raw reply payloads.

For vehicle verification, start live data with the default selection, leave the
app in the foreground for 60 seconds, inspect RPM's observed update interval and
copy the diagnostic. Repeat with twelve supported signals and with RPM alone.
Check that other signals keep receiving data, Stop stops new reads, and a
disconnect requires explicit reconnection. Scripted tests and emulator checks
do not qualify any physical adapter or guarantee a vehicle update rate.

The controller regression test runs twelve advertised PIDs for sixty simulated
seconds with each scripted reply taking 80 ms. It completes 600 reads, including
119 RPM readings (mean interval 504 ms, maximum 600 ms), and all twelve final
receipts remain younger than five seconds. Stop prevents further reads. These
numbers describe that deterministic test fixture, not measurements from a car.

With 13 or more selected signals, the scheduler gives an overdue RPM read
priority, then rescues other signals approaching the five-second freshness
limit, while preventing consecutive RPM reads when another PID is due. The
existing 24-signal regression remains. Full-catalog tests additionally cover
80 ms, 700 ms and timeout-speed replies, no-starvation behavior and NO DATA
recovery. A full watch may exceed freshness targets even on a working adapter.
The app shows measured intervals, receipt ages and stale states, not a fixed
rate promise.

When Vehicle Health is enabled, speed/fuel-system status receive best-effort
priority after five seconds without a receipt, and ECU status after 35 seconds.
Only due requests qualify, preserving the ECU status interval and NO DATA
backoff. Priority reads alternate with ordinary non-RPM service to avoid
starving other sensors under overload. Full-watch health tests exercise all
scalar PIDs plus these structured inputs at 200 ms reply latency; they also
check fairness when replies approach the transport timeout. Existing health
expiry thresholds remain unchanged.

Owner-operated on-car checks should compare the full supported watch with
smaller 9-, 12-, 16- and 24-sensor watches over 60 seconds, including RPM and
health-context gaps, stale/unavailable counts and responsiveness of Stop.
These checks require the owner's explicit hardware operation; synthetic tests
do not establish vehicle qualification.

## References considered

- [ELM327 v2.3 datasheet, Communicating with the ELM327](https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf):
  the prompt indicates that the interpreter is idle and ready for the next command.
- [kotlin-obd-api connection implementation](https://github.com/eltonvs/kotlin-obd-api/blob/master/src/main/kotlin/com/github/eltonvs/obd/connection/ObdDeviceConnection.kt):
  mutex-serialized commands, a default delay of zero and prompt-based completion.
  Reviewed as a reference; no dependency or source code was incorporated.
- [usb-serial-for-android FTDI driver](https://github.com/mik3y/usb-serial-for-android/blob/master/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/FtdiSerialDriver.java):
  reviewed read and latency handling. The existing pinned USB driver and UART
  configuration are unchanged; timing evidence should precede driver tuning.
