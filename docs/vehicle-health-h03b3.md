# H03B-3 synthetic controller integration

This change adds internal, explicit acquisition APIs only. All assessment profiles
remain synthetic and unregistered. There is no assessment UI, rule activation,
diagnostic conclusion, new threshold, new wire command or physical adapter work.
EVIDENCE_READY means acquisition readiness, never vehicle health.

## One session and one worker

`NativeController.startAssessment(profile, instanceId, operation)` reserves the
existing controller owner and returns `ControllerAdmission` immediately. Rejected
operations are not queued. VIN and snapshot requests and live start now return
the same typed admission outcomes and reserve their operation before returning.
Disconnected, background, stopping-live, empty-live-selection, busy,
cleanup-uncertain, running-live and unmapped-context cases have distinct typed
outcomes. An active assessment blocks those operations and another assessment. Explicit
cancellation ends the instance; a replacement may start only once any native read
has returned. `replaceAssessment(profile, instanceId, operation)` cancels the old
instance first and attempts immediate admission; it returns BUSY while the old
native read is pending. No replacement waits in a hidden queue.

PASSIVE can attach to an already-running live watch. It accepts only subsequent,
completed scalar dashboard receipts selected by that watch. It does not use
snapshot, replay, simulator, or health-only receipts, and does not issue reads.
Missing inputs stay missing until existing dashboard acquisition provides them
or the budget ends. Starting passive while stopped never starts live acquisition;
it can be cancelled or expire without measurements. Its command count stays zero.
The collector shares its validated receipt path, input reasons and exact cohort
selector with focused acquisition, including capability, identity and NO DATA
checks. Optional inputs cannot delay required readiness.

FOCUSED requires live acquisition, stop acknowledgement and any pending native
read to have ended. It uses the same serialized worker and only typed
`NativeSession.read(pid)` and `readStructured(0x03)` calls. It preserves gauge
selections, requested rates and the ordinary H02B plan; it does not insert health
reads beside gauges and never restarts live. Existing advertised capability,
framing, size and allowlist checks remain independently enforced by NativeSession.

## Bounds and publication

The collector's original total budget and command cap still apply. Before action
admission and again immediately before the native call on IO, the owner accounts
for remaining 200 ms pacing and the entire standard 3,000 ms native exchange
allowance. A dispatch with remaining time at or below that full allowance ends
PARTIAL / TIME_BUDGET: equality leaves no room for an admissible completion
strictly before the collector deadline. A slot declined
at the final IO boundary without a native call is returned to the collector.
The native timeout is never shortened. These are synthetic acquisition bounds,
not diagnostic thresholds or hardware qualification claims.

Each successful native return is timestamped individually on IO, including exact
NO DATA returns. Focused receipts pass through HealthObservationBridge and are
stored only in assessment evidence; they do not refresh stopped dashboard cards
or overwrite the ordinary live health history. NO DATA consumes a command and
resets the signal's relevant windows; later receipts can recover within the
original budget/cap. Transport and protocol faults end the assessment and session
without automatic retry. Release failure retains ownership and blocks reconnect
until explicit release succeeds.

Cancellation revokes an atomic dispatch permit. A queued IO action checks the
permit before sending; an already-running bounded exchange can finish but cannot
publish into a cancelled or replaced instance. Owner cancellation, disconnect,
lifecycle stop, and faults are terminal. Generation and collector identity checks
reject late completions. Activity `onStop` continues to disconnect through the
process-owned controller; foreground return does not reconnect or resume live.
Controller API/state mutations remain confined to its UI owner context; only
native work, immutable deadline checks and the atomic permit cross to IO.

## Clock and context policy

Android controller receipts, Compose freshness ticks, transport pacing/deadlines
and replay interval production all use `SystemClock.elapsedRealtime()`. This
includes device suspend time. Controller and replay test clocks remain injectable;
pure freshness consumers accept an explicit time. Future or negative dashboard
receipt timestamps have unknown age and cannot display a fresh value. Assessment
receipts outside their instance lifetime cannot qualify. UTC and original replay
capture offsets remain capture facts and are never substituted for receipt time.
Old controller receipts cannot survive a process clock-domain migration.

There is no reviewed mapping from observations to operating-context facts yet.
The controller accepts no arbitrary context assertions, initializes context empty,
and returns CONTEXT_DEFERRED for any profile naming context keys. Such profiles
must wait for an observation-backed mapping and its tests. The existing collector
context-window contract remains available as a library boundary, not an Android
assertion API. H03B-4 must review real applicability and delivery; H03C must review
diagnostic rules, reference ranges, thresholds, findings and wording separately.

## Scripted evidence

Collector and controller regressions cover passive exact wire-log identity and
capability checks, shared cohort/reason behavior, successful focused structured
and scalar repeated cohorts, individual receipt times, watch/rate preservation,
exclusive immediate admission, stopped-read acknowledgement, context deferral,
pacing/full-timeout budget boundaries (including equality at owner and IO
admission), delayed IO dispatch, six required inputs at the exact command cap,
repeated passive cross-signal cohorts, exclusion of health-only receipts, NO DATA
recovery, faults, failed cleanup, lifecycle cancellation, replacements and late
completions. A shared injected elapsed clock drives receipt ages and replay across
a simulated suspend gap; incompatible future receipts cannot appear fresh.
The AGENTS.md JVM/Android host tests, app unit tests, lint and assemblies are the
required checks. All transport tests use scripts; no hardware is accessed.
