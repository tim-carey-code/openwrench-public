# Fuel-trim visibility and offline saved-drive review

The live health reader previously collected its additional scalar inputs without
publishing them to dashboard display state. A trim could therefore appear in a
health report while its pinned dashboard card remained blank unless the user
separately selected it. The default dashboard layout also omitted trims.

Actual scalar receipts collected by ongoing health monitoring now feed the
existing dashboard presentation and trend history. Freshness, NO DATA, source,
generation and lifecycle rules remain unchanged. Unselected monitor-only display
receipts are removed when monitoring ends; they cannot remain falsely current.
Finite assessment-only acquisition keeps its existing isolated display behavior.

The hardware dashboard has a **Show fuel trims** action. It selects and pins
advertised short/long-term trims for Banks 1 and 2, puts them in the first card
slots, and preserves other pins. Unsupported trims are not selected. While
stopped, this explicit action selects the trims and starts live acquisition.
While running, supported missing trims are added to the same owner's read plan
between exchanges. Existing acquisition rates, receipt ages, NO DATA retry
backoff and in-flight results are retained. The preset does not restart the
reader or end an ongoing Vehicle Health drive. Health-only trims already being
read are promoted at their existing rates; promoted trims continue after Finish
drive report as ordinary selected dashboard signals.

Selection is atomic and respects the 24-signal limit. If there is not enough
room for every advertised trim, the preset keeps the current selection and
shows a dialog linking to All signals. This cap includes health-only scalar reads.
No signals are silently removed and
no unsupported trims are requested. The session log records selection changes
without clearing the run's earlier receipts.

Signed values retain their percent units. Trim cards use observation traces
rather than the ordinary nonnegative percent bar used for load/throttle.

## Older drives need no new road test for offline review

Opening a saved drive now produces an **Offline review of saved evidence**.
It uses retained scalar counts, moving/stationary aggregates, trim ranges and
original assessment categories. Negative/positive aggregate ranges explain the
recorded learned-correction direction; they do not qualify a continuous window
or establish a faulty part. They do not provide RPM/load-specific good/bad
judgments. Original findings retain their original rule version.
The original archive is not modified or reevaluated under invented context.
The copied saved-drive report includes this offline review once.

Two completed older drives can also be compared after same-vehicle confirmation
and the existing matching observed-source check. If newer typed operating windows
are missing, the result explicitly compares recorded review categories and
whole-run ranges only. It does not claim matching RPM/load/temperature conditions,
matching correction direction, a recurring cause, improvement or repair success.
Newer qualified window comparisons retain the prior stricter gates.

No adapter, new drive, VIN, raw command, network service or schema migration is
needed for this review. Missing pending/permanent code identifiers, freeze frames,
or receipt-level operating context cannot be created from an older archive.
Install this APK over the existing tester package to preserve its app-private
history; uninstalling or clearing app data removes it. Versions 1/2/3 remain
readable. Text reports alone are not imported into typed drive history by this
change, but can still be reviewed outside the app.

Regression tests cover shared live receipts, unchanged monitoring read plans,
continued trim acquisition after finishing, cleanup without promotion, atomic
selection limits, unsupported display inputs, signed values, old offline evidence,
no invented comparison windows and preservation of original files/findings.
Synthetic emulator checks cover the dashboard preset and historical review on
phone layouts. A synthetic QA app using the real NativeApp and NativeController
also verified the live trim button during ordinary polling, initially stopped
acquisition, and ongoing Vehicle Health; both LTFT banks displayed actual scripted
receipts. The full-read-plan dialog was inspected on a phone at 1.6x system text.
Physical vehicle qualification remains pending.

The next increment uses assessment pack 3 and archive format 4; see
[contextual fuel-correction evidence policy](contextual-fuel-correction.md) for
paired STFT/LTFT intervals, retention, classification and version compatibility.
Older results and their original rule versions remain unchanged.
