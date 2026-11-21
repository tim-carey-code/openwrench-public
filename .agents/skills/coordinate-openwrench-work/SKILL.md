---
name: coordinate-openwrench-work
description: Coordinate or resume OpenWrench work, triage a proposed task, recover prior decisions, or check whether work repeats an existing implementation. Use before feature implementation and when handing off between contributors or agents.
---

# Coordinate OpenWrench work

Read root `AGENTS.md`, `docs/collaboration.md`, and the relevant task thread,
issue, or PR before planning. Tim and Nick own scope, priorities, assignments,
implementation decisions, and issue closure. This workflow grants no additional
permission to post, merge, or access hardware.

## Evidence before another implementation

1. Record current main SHA, task branch/head, working-tree status, and registered worktrees. Do not change another contributor's checkout. If repository or GitHub access is missing, name the missing source; never report that no prior work exists.
2. Search both open AND closed issues/PRs using the requested behavior, affected subsystem, and likely file/symbol names. Inspect relevant merged diffs, current source/tests, and available task decisions. Titles and an old task list are not proof something remains undone.
3. Classify the request: **already implemented**, **partial gap**, **regression**, **new work**, or **unknown**. Cite exact current code and previous issue/PR evidence. For a gap or regression, state the observable difference; for unknown, identify the smallest read-only check needed. Do not rebuild an existing feature merely because its implementation differs from an old plan.
4. Continue in the existing task thread and link any relevant issue or PR. Do not
   require an issue or a separate status log. Record consequential decisions and
   exact-head evidence on the authorized PR.
5. Agree scope and file ownership before editing. Coordinate overlaps and get
   explicit acknowledgment when handing off. If records conflict, ask the human
   decision owner and continue only with uncontested work.

## Repeat experiments only with a reason

Read prior results before rerunning an investigation. State what changed (commit, environment, input, hypothesis), or why an explicit reproduction is needed. A deliberate regression test is useful; another identical implementation without checking the first is not. Keep facts, hypotheses, and untested claims distinct. When evidence conflicts, preserve both observations and resolve the conflict rather than silently replacing history.

## Handoff

Return classification, source links/SHAs, relevant issue/PR if any, scope, owner, blockers, and one next action. Share an update after a meaningful result or decision, not after every command. Any new head invalidates assumptions that review/CI for an older head covers the new code.
