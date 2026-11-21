# OpenWrench Checks

Use the changed code and current project contracts to choose relevant checks. These are review prompts, not universal findings or frozen implementation requirements. Demonstrate the actual path before reporting a defect.

| Area | What to examine when affected |
| --- | --- |
| Adapter transport and ownership | Verify a single command/response owner; framing and request/ECU association; bounded reads/retries; cancellation and disconnect; failed cleanup; reconnect eligibility. Check whether callbacks or queued work from an old connection generation can publish into a new session. |
| Polling and health scheduling | Trace dashboard, health, scan, and watch consumers through shared polling. Check starvation, duplicate polls, registration cleanup, cancellation, and bounded work. Derive capability/rate/signal limits from current code and the active adapter/vehicle. Check connected-but-idle behavior when the feature requires sampling. |
| Observation identity and validity | Keep value, units, observation time, source, ECU/bank, and availability attached to the same sample. Distinguish real zero from absent, stale, unsupported, malformed, or delayed data. Invalidate prior-session capabilities and readings at the intended lifecycle boundary. |
| Diagnostic interpretation | Verify the evidence and operating conditions required by each finding. Check bank identity, fuel-trim pairing, closed-loop state, temperature, motion, RPM/load windows, and applicability where the evaluator uses them. Distinguish MIL state, DTC results, readiness monitors, missing evidence, and health conclusions. |
| Command policy | Compare emitted commands against the current approved diagnostic allowlist, including discovery, retry, error recovery, and raw-command routes. Preserve read-only vehicle behavior. Do not hardcode an obsolete Mode 01-only policy when current source defines legitimate additional read services. |
| Android lifecycle and UI | Trace permissions, activity recreation, backgrounding, recomposition, cancellation, and hot unplug. Check for duplicated collectors or transport work and expensive parsing/I/O on the UI thread. Distinguish loading, empty, partial, failed, cancelled, and completed results so failures do not become a blank screen or success state. |
| Scan and drive persistence | Verify durable save completion before success is reported. Follow transaction failures, partial data, identifiers, reload, serialization, and migrations. Distinguish normal app upgrades from uninstall/clear-data behavior; do not assume every APK update preserves or erases data. |
| Historical comparisons | Use retained evidence and the comparison policy actually implemented. Verify vehicle identity/provenance, policy compatibility, bank identity, and overlapping operating windows where required. Do not reconstruct unrecorded samples or equate an explicit same-vehicle assertion with verified VIN/ECU identity. |
| One-button diagnostics | Follow the action through connect/discovery, required sampling, evaluation, results, and saving. Check repeated taps, cancellation, timeout, bounded retry, partial results, useful failure messages, and cleanup. Show a concrete failure path before flagging workflow completeness. |
| Privacy and diagnostics | Check exports and logs for unnecessary identifiers, raw pairing/secrets, or misleading certainty. Preserve useful diagnostics while making failures and evidence limitations distinguishable. |
| Regression evidence | Inspect tests for the changed behavior and realistic failure/race paths: delayed callbacks, reconnect, cancellation, unsupported inputs, save failures, and relevant migrations. Prefer focused tests to blanket suites; distinguish source-level confidence from physical qualification. |

## Evidence and severity

Tie every finding to the reviewed change, a triggering scenario, an affected execution path, and an observable consequence. Link supporting current source/tests and the relevant changed location. Keep unrelated existing debt and optional enhancements out of the findings.

Treat a passing check as evidence only for what it exercised. Check reports from older heads and synthetic fixtures cannot establish that the reviewed head works with an actual adapter or vehicle. Report these limitations as validation scope, without inventing a failure.
