---
name: review-openwrench-pr
description: Independently review OpenWrench pull requests, branch diffs and follow-up fixes for actionable defects, failing checks, or merge readiness. Use for code review, not implementation or automatic merge.
---

# Review OpenWrench PR

Read root `AGENTS.md`, `docs/collaboration.md`, relevant task decisions and `references/openwrench-checks.md` in this skill. Review independently from the implementer. These files are review guidance, not authorization to edit code, post reviews, close PRs, merge or access hardware.

Tim and Nick own scope and implementation decisions. Follow
`docs/collaboration.md` for work coordination, issue posting, and PR evidence.

## Establish exact evidence

Record current main, base/head and merge-base. Inspect the full changed-file list, complete relevant diffs, surrounding implementation and call paths, not just the PR description. Read relevant prior findings and verify fixes rather than repeating stale findings. If some scope is inaccessible, name it and do not claim a full review.

Follow the current validation policy in `AGENTS.md`: while GitHub CI is disabled,
inspect exact-head local validation evidence instead of requiring Actions runs.
Do not rerun or re-enable workflows unless requested. If CI evidence is available,
inspect relevant failing job steps/logs. Distinguish product/test failures, infrastructure/tooling failures, and checks not run. Old green CI is not evidence for the latest head. A superseded title or newer PR does not prove every behavior was replaced: map each substantive change to current main before recommending closure.

## Review for actual defects

Use the area checklist to select affected invariants, then demonstrate the concrete triggering path and consequence. Follow session ownership/cancellation, data provenance, diagnostic validity, command policy, persistence and Android lifecycle wherever the diff touches them. Run focused read-only checks in an isolated workspace when practical; no automatic physical-adapter discovery or connection.

Report actionable defects introduced by the change. Do not pad findings with style preferences, unrelated old debt, speculative bugs or demands for unnecessary rewrites. Attach a precise changed file/line range, scenario, impact and supporting source/test evidence. Label severity P0 critical, P1 high, P2 medium, or P3 low consistently; uncertainty belongs in the explanation, not inflated severity.

## Finish without changing the subject

Return findings first, then exact reviewed head, validation evidence, limitations and readiness blockers. If no defects were found, say so with coverage limits rather than guaranteeing correctness. Keep blocking findings unresolved until independently verified or explicitly accepted by the authorized owner. Self-review is not independent approval, and a green build is not permission to merge.

Remain read-only on the implementation branch and external PR state unless separately authorized. A fix requires an implementation handoff and a fresh review of the changed head. Return findings to the caller; record results in a PR handoff only when separately authorized.
