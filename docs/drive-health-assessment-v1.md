# Drive health assessment, first rule pack

Vehicle Health interprets evidence from the existing foreground monitor. It
updates during a drive, freezes when monitoring ends, and is saved with the
recap, charts and warning bookmarks. No separate timed capture is required.
The assessment does not add vehicle requests, start another reader, collect a
VIN, or use a network service. The ordinary dashboard schedule and transport
ownership remain unchanged.

## Findings and scope

Each check has an outcome, a plain-language explanation, receipt evidence,
operating context, limitations and suggested next checks. The outcomes separate
reassuring reported evidence, items to review, observed patterns, insufficient
data, and systems not assessed. There is no whole-vehicle score, roadworthiness certificate or
failed-part conclusion. A clear ECU status cannot erase an earlier warning
observed during the same monitored drive.

The first pack interprets MIL and ECU-reported code count, emissions readiness,
reported fuel-control states, and sustained learned fuel correction. Incomplete
readiness means the self-check has not finished; it is not a component failure.
Stored-code identifiers still require the separate explicit read-only scan.
PID 01 does not establish the dashboard lamp's flashing state or fault severity.

Fuel-trim screening uses actual receipts with fresh engine/motion and exact
closed-loop feedback context. Moving and stationary windows remain separate.
Open-loop warm-up, load/fuel-cut, feedback faults, unknown context, NO DATA and
long receipt gaps cannot qualify a sustained window. The two reported fuel
systems are not assumed to identify cylinder banks. Closed loop does not prove
that the engine is fully warm.

A qualifying trim window needs at least ten LTFT receipts spanning thirty
seconds, no LTFT gap over six seconds, and context receipts no older than ten
seconds. Its raw window is bounded to sixty seconds. These are evidence-quality
policies. The first qualified excursion is retained for drive review even if
later correction changes. Current clear ECU/readiness status expires after
forty-five seconds; fuel-control context expires after ten seconds.

The 10% LTFT magnitude cue is a disclosed screening policy, not a verified
manufacturer limit or a DTC threshold. A sustained excursion suggests comparing
the learned correction against the vehicle's service information under the
specified test conditions. Positive correction means fuel is being added;
negative correction means fuel is being removed. Neither identifies the cause.
Remaining inside the cue does not establish a healthy fuel system.

Coolant and module-voltage measurements remain available in the recap. Without
verified vehicle specifications they do not yield thermostat, cooling-system,
battery-capacity or alternator pass/fail conclusions. ABS, airbags, body systems,
dedicated transmission-module health and mechanical condition remain unassessed.

## Evidence and persistence

Evidence qualification and numeric cue values are versioned application policy.
They are separate from OEM diagnostic thresholds. Receipt times are sequential
adapter observations, not simultaneous ECU samples. The assessment uses the
reader's typed receipts, never retrospective inference from chart decimation or
min/max aggregates. Finding storage is bounded independently of drive length.
Live freshness is evaluated at presentation time; saved findings remain
historical at their recorded offsets and do not resume or become live on reopen.

Archives write `openwrench.drive-history/3` and read the previous `/1` and `/2` formats.
Old drives retain their recap and charts and explicitly lack a recorded
assessment. They are not retroactively evaluated using incomplete context.
Unknown or corrupt formats remain protected by the existing store rules.

## Rule references

- [CARB OBD-II fact sheet](https://ww2.arb.ca.gov/resources/fact-sheets/board-diagnostic-ii-obd-ii-systems-fact-sheet):
  warning-light scope, follow-up, and incomplete readiness interpretation.
- [Snap-on, Fuel Trim Adaptation](https://www.snapon.com/EN/UK/Diagnostics/News-Centre/Technical-Focus-Archive/fuel-trim-adaptation):
  correction direction, learned adaptation, and the relationship between short
  and long trims. This pack does not combine asynchronous extremes into a total.
- [Bosch Global OBD-II operator manual, page 6-4](https://cdr.boschdiagnostics.com/pro/sites/pro/files/tech_1a_global_obd_ii_operators_manual.pdf):
  LTFT depends on engine design and operating conditions; its 10% guidance is
  specifically for a warm engine at idle, not a universal driving limit.
- [Innova, Reading Fuel Trim in Live Data](https://www.innova.com/blogs/fix-advices/reading-fuel-trim-in-live-data):
  general 10% follow-up guidance. This is used only as a screening cue. Its
  suggested component causes are not implemented as diagnostic rules.

## Verification and next on-car check

Synthetic tests must cover clear and warning ECU states, later clears, incomplete
and unsupported monitors, fuel-feedback faults, sustained corrections, missing
context, NO DATA, context changes, stale recovery, unsupported inputs, stopped
historical results and archive compatibility. Emulator inspection covers live,
finished and reopened findings with enlarged text. These checks do not qualify
an adapter or vehicle.

For the next test, start monitoring while parked, take an ordinary drive and
finish while parked. Review the assessment's explanations and coverage gaps,
copy the report, then restart the app and reopen the saved drive. Compare any
review item to its evidence rather than expecting every system to receive a
pass. A drive without warnings does not validate warning-detection paths on
physical hardware.

Qualified operating windows and the current comparison behavior are described in
[warning context and comparisons](diagnostic-context-and-comparisons.md).

The next increment uses assessment pack 3 and archive format 4; see
[contextual fuel-correction evidence policy](contextual-fuel-correction.md) for
paired STFT/LTFT intervals, retention, classification and version compatibility.
Older results and their original rule versions remain unchanged.
