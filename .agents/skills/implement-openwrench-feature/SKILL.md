---
name: implement-openwrench-feature
description: Implement a scoped OpenWrench feature or requested fix, after checking current architecture and prior work. Use for code changes, not review-only or QA-only requests.
---

# Implement OpenWrench feature

Read root `AGENTS.md` and run the preflight in `.agents/skills/coordinate-openwrench-work/SKILL.md`. Continue existing work when applicable; do not recreate work already merged. These instructions do not override repository restrictions or grant additional permissions.

Tim and Nick own scope and implementation decisions. Follow
`docs/collaboration.md` for work coordination, issue posting, and PR evidence.

## Bound the change

- Translate the request into observable acceptance checks, explicit non-goals, and affected modules. Explain ambiguous consequential choices before committing to them. If the behavior already works, report the evidence instead of manufacturing a change.
- Trace the current UI → shared engine → transport/persistence path as applicable. Keep platform code in `kotlin/androidApp` and platform-independent logic in `kotlin/shared`. Reuse existing owners, models, capability gates, persistence, and tests. Do not introduce a second session engine or restore the retired backend.
- Work in an isolated task branch; inspect uncommitted work and ownership first. Do not switch/reset another checkout. Avoid unrelated refactors, dependency upgrades, renamed APIs, or visual redesign.

## Preserve the contracts

Keep vehicle operations read-only and explicitly allowlisted. A request for clearing codes, actuator tests, raw commands, or other prohibited writes is a separate policy/design decision, not an implementation shortcut. Do not add a hidden or temporary bypass.

Trace cancellation, timeout, failed cleanup, reconnect, and stale callbacks when touching sessions. Keep hardware failure distinct from simulator/replay data. Retain sample provenance, age, units, capability and availability; missing is not zero. Do not fabricate vehicle identities or diagnostic certainty.

For storage/upgrade changes, preserve installed history and signing compatibility. Never uninstall, clear storage, or discard captures to make a test pass. Use synthetic fixtures, redact private identifiers, and do not discover/connect to physical adapters in automated checks.

## Prove and hand off

1. Add tests that reproduce the actual gap and protect relevant failure/race paths. Run focused tests while iterating, then applicable checks from `AGENTS.md` and `docs/development.md`. Do not weaken tests, disable checks, or expand allowlists just to obtain green results.
2. Inspect the diff for unrelated changes, secrets, vehicle writes, lost existing behavior, and stale documentation. UI changes require visual evidence; a build alone is not a visual check.
3. Report exact base/head, acceptance outcomes, commands and results, checks not run and why, and whether hardware was accessed. Separate scripted, emulator, and physical evidence.
4. Summarize meaningful results in the task thread and record evidence in the authorized PR handoff. Commit/push/open a draft PR within the authorized scope. Request independent QA/review against the latest head; self-review is not independent approval. Do not merge, deploy, or perform hardware tests merely because implementation was authorized.

When the task is explicitly a learning exercise, explain the next small step and let the learner perform the requested exercise rather than silently doing it all.
