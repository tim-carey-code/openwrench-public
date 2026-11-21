# H03B-2 synthetic assessment acquisition contract

H03B-2 adds a deterministic, bounded collector and evidence cohort selector in
`shared`. It is a library boundary only. No profile is registered with Android,
no rule pack is activated, and `EVIDENCE_READY` means that measurements meet an
acquisition profile. It makes no claim about vehicle health. All example profiles,
sample counts, timing limits, and values in the tests are **synthetic**.

## Ownership and commands

`AssessmentCollector` receives a separate profile ID/version, assessment-instance
ID, session generation, source, current advertised PIDs, context, and injected
monotonic clock. H03B-3 aligns Android receipt and interval clocks on `SystemClock.elapsedRealtime()`. The collector returns one
typed `Scalar(pid)` or `FuelSystemStatus` action at a time. The existing owner must
execute it on its serialized worker using `NativeSession.read(pid)` or
`readStructured(0x03)`, normalize the resulting `NativeReading` through the H02
bridge, then call `complete` or `fail`. It owns no transport, connection, worker,
polling loop, or raw-command API. The [H03B-3 controller integration](vehicle-health-h03b3.md) supplies scheduling and admission;
ordinary live reads still use the H02B cadence gate. A protocol or transport exception is reported with `fail` and
terminates the assessment without automatic retry.

The profile constructor accepts only explicitly allowlisted Mode 01 scalar PIDs
and structured `0103`. It checks the declared signal ID, value type, and
canonical unit against `NativeSignalCatalog`, which the production Android
observation bridge also uses. An alias such as `engine-speed` for `010C` or an
integer declaration for its numeric `rpm` value is rejected before dispatch.
The production `NativeSession` still enforces
its own independent capability, command, timeout, size, and framing checks. An
unadvertised required input makes dispatch `DEFERRED` before any command is sent.
An unadvertised optional input cannot block readiness.

## Synthetic bounds and accounting

The current synthetic contract permits at most six total inputs, one or two
samples per input, a total budget of 1–60,000 ms, a command cap of 1–24, and a
cross-signal receipt gap of 0–10,000 ms. Each input retains at most four
observations. These limits bound selection work and memory; they are **synthetic
test limits**, not vehicle diagnostic thresholds or a promise of sufficient
physical adapter time. The existing 3-second native exchange timeout is unchanged.

The total budget starts when the collector is constructed and never restarts
after context changes or `NO DATA`. Before dispatch, the collector checks the
elapsed budget and command cap. Each dispatched action consumes one command slot,
including `NO DATA`, a foreign receipt, or a failed exchange. It checks elapsed
time again when a completion arrives. A completion at or after the exact deadline
cannot publish evidence. Refresh also checks the deadline before selecting a
cohort, so a clock advance after completion admission cannot publish late
evidence. The cap permits the last dispatched action to complete
and qualify evidence; if it does not, the result is `PARTIAL`. A session owner
must not shorten the native exchange timeout to fit a remaining assessment
budget. It may decline dispatch when the remaining budget is too short for its
own policy; the collector itself does not invent a shorter exchange bound.

## Cohort and context policy

Required inputs determine readiness. Optional inputs may be offered from an
already completed owner read through `offerExistingOptional`; this never sends
an assessment command or increments its command count. They are included only
if they form a valid cohort alongside the required inputs. They never delay
readiness. A required input may list only other required inputs in
`invalidatedBy`; a prerequisite that can reset required evidence cannot be
optional. When optional inputs cannot all join the same cohort, selection
considers them in signal ID order regardless of profile declaration order.
A profile must mark every genuine safety prerequisite as required.

The selector chooses exactly one receipt per required input in each complete
cross-signal group. The number of groups is the largest declared minimum sample
count, currently one or two. Every selected receipt must be in one such group;
each group's earliest-to-latest receipt span must be within the profile's
cross-signal gap. When two groups are required, every selected input supplies
two distinct receipts, and their receipt span must meet that input's minimum
window. Thus one close group plus unrelated extra samples cannot qualify. Each
selected receipt must meet its input's maximum age at the decision time. Receipt
time is local monotonic completion time, never an inferred ECU sample time.
Dispatch prioritizes required inputs that have not met this shared group count
or their own freshness and window requirements, including inputs whose declared
sample minimum is smaller. Optional sample minimums do not increase required
dispatch counts. For example, required inputs with minimums of one and two
samples each need two receipts to complete the cohort; with compatible timing,
four commands suffice without redundant reads.
Input IDs and receipt times are sorted before search, so equivalent input
orderings return the same exact evidence. The output retains each original
observation's source, session, PID, type, unit, known module identity, and
structured `system_1`/`system_2` fields. Unknown ECU identity stays null;
profiles requiring a verified module cannot qualify unknown identity.

Each `AssessmentInput` names the context keys that its sampling window depends
on. A changed value or removal clears only affected windows, while elapsed time
and dispatched count continue. A pending affected action is discarded on
completion. An input can also name signals whose `NO DATA` invalidates it.
`NO DATA` ends that signal's run and all declared dependent windows, then later
sampling may recover within the remaining budget. The collector records each
invalidation time and rejects a later delivered receipt at or before that time,
including passively offered optional receipts. No data from another source,
session generation, cancelled instance, or replaced instance can publish
qualification. The owner must cancel the old collector when replacing an
assessment. Retention is capped at four receipts per input.

`COLLECTING` means actions may still be issued; `EVIDENCE_READY` is terminal
measurement readiness; `PARTIAL` means the budget or cap ended before readiness;
`DEFERRED` means a required advertised capability is absent; `CANCELLED` is an
owner stop; and `FAILED` is a transport/protocol or non-`NO DATA` read fault.
Each snapshot reports input-level qualification reasons, whether a time or
command limit ended a partial assessment, and the exact selected
cohort when ready. The H03A `HealthEvaluator` remains the only future diagnostic
evaluation boundary and is never called by acquisition.

H03B-3 integrates dispatch with `NativeController`'s one owner, lifecycle,
gauge freshness and Android elapsed-realtime clock domain. H03B-4 must review
real assessment applicability and the delivery/qualification boundary before
activation. H03C must separately review any diagnostic rules, thresholds,
reference ranges, findings, and wording. Scripted tests do not qualify a
physical adapter or vehicle.

## H03B-3 extensions

`AssessmentCollectionMode.PASSIVE` disables `nextAction` and exposes
`offerPassive` for required as well as optional receipts. It uses the same receipt
validation, NO DATA invalidation, input reasons and exact cohort selector as
focused completion. Receipts must belong to the current source, session and
instance, fall within the assessment lifetime, and match advertised capabilities
and declared identity/type/unit. Passive collection does not consume command
slots or change its owner's wire sequence.

`nextAction(requiredAllowanceMillis)` can decline dispatch when the remaining
budget cannot accommodate the owner's pacing plus full exchange allowance.
The controller rechecks at the IO boundary as well, because scheduling can
consume that allowance. `declineDispatchForTimeBudget` releases an admitted slot
when no native exchange was started and terminates with TIME_BUDGET. Neither
path changes native timeouts. The owner can also report a passive session fault
with `fail(description)` without inventing a read action.
