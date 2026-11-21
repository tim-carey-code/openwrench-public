---
name: verify-openwrench-change
description: Independently QA an OpenWrench feature, reproduce a reported defect, test acceptance criteria, or reassess a fix. Use for behavioral verification; do not silently modify the implementation.
---

# Verify OpenWrench change

Read root `AGENTS.md`, `docs/collaboration.md`, and the relevant task thread or
PR, if any. Independently derive expected behavior from requirements and current contracts, not just the implementer's summary. Use a different reviewing agent/person from the implementer; if unavailable, label work self-checking, not independent QA.

Tim and Nick own scope and implementation decisions. Follow
`docs/collaboration.md` for work coordination, issue posting, and PR evidence.

## Establish the subject

Record exact base/head, artifact/build identity, environment and prior evidence. Inspect current code and relevant tests. Check prior evidence before rerunning work; state why repetition adds evidence. A claim of a fix, CI on an older head, and a screenshot from another build are not proof for this head.

## Risk-based tests

Choose tests from the changed paths and acceptance criteria. Include relevant normal, boundary, unsupported, malformed, cancelled, timed-out and repeated-action cases. For transport/lifecycle changes, examine reconnect, hot unplug, old callbacks, ownership and failed cleanup. For diagnostics, test missing/stale data, capability gates, source attribution and real zero. For persistence, test save failure, reload and applicable migrations without erasing installed history.

Use scripted transports and synthetic data. Do not discover or connect to adapters, send vehicle commands, install an APK over a person's app, uninstall, or clear storage without task-specific authorization. Lack of hardware evidence is a limitation, not permission to obtain it. Use a disposable test workspace; keep the implementation branch untouched and never weaken tests to match a bug.

Run applicable repository commands and report pass, fail, not run, or blocked separately. For UI work, inspect the actual device/emulator presentation when authorized and available; otherwise leave visual acceptance unverified. Compilation and scripted tests do not qualify real hardware.

## Report

For each failure provide requirement, exact revision, input/preconditions, reproducible steps, expected/actual behavior, consequence, and minimal safe evidence. Distinguish confirmed failures from hypotheses. Report acceptance criteria individually; do not say 'all good' when material criteria remain untested.

Return a verdict with blockers, passed checks and limitations. Return findings in the task thread; update only an authorized PR handoff. Reviewers remain read-only: hand fixes back to the implementation owner and retest the new exact head independently. Passing QA is not merge approval.
