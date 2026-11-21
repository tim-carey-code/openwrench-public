# Shared OpenWrench agent playbooks

These repository-owned skills are for everyone working on OpenWrench. They ship
with the checkout, not one person's private assistant account. `AGENTS.md`
remains the project's baseline; skills supply task-specific procedures.

| Task | Skill |
| --- | --- |
| Investigate an assigned issue without editing code; report for human decision | [scout-openwrench-issue](../.agents/skills/scout-openwrench-issue/SKILL.md) |
| Recover context, check prior work and coordinate the handoff | [coordinate-openwrench-work](../.agents/skills/coordinate-openwrench-work/SKILL.md) |
| Implement a bounded feature or fix | [implement-openwrench-feature](../.agents/skills/implement-openwrench-feature/SKILL.md) |
| Independently test acceptance and regressions | [verify-openwrench-change](../.agents/skills/verify-openwrench-change/SKILL.md) |
| Independently review the exact code diff | [review-openwrench-pr](../.agents/skills/review-openwrench-pr/SKILL.md) |

## Use with Codex or another agent

After these files are merged, update your own clean checkout through your normal
Git workflow. Do not discard local changes to get the skills. Open the project
in a Codex version that supports repository `.agents/skills` discovery; verify
the skill appears before relying on automatic invocation. If the session has
cached discovery, reopen it. Example prompts:

- `Use $scout-openwrench-issue to investigate issue #<number>, post an evidence report and proposed fix there, and stop before editing code.`
- `Use $coordinate-openwrench-work to check whether this task is already done. Read the task context and current main (plus the issue, if one exists); report evidence before editing.`
- `Use $implement-openwrench-feature for the remaining acceptance gap in #<number>. Keep the existing behavior and record implementation evidence in the PR.`
- In a separate reviewer context: `Use $verify-openwrench-change to independently test PR #<number> at its latest head.`
- In a separate reviewer context: `Use $review-openwrench-pr to review PR #<number>; report defects and validation limits without editing it.`

Other agents may not discover this directory or understand `$skill-name`.
Explicitly ask them to read root `AGENTS.md`, the relevant `SKILL.md`, and its
referenced files from this checkout. Do not assume copying a skill grants tool,
repository, or hardware access. If an account-level skill has the same name,
explicitly identify the repository path to avoid ambiguity.

## Work from shared context

Use Telegram for coordination and GitHub PRs for the durable change record.
Read the [collaboration guide](collaboration.md) for ownership, handoffs, issue
posting, and reviews. Use available context, not private chat assumptions.
Check prior merged work and current behavior before repeating an implementation
or experiment.

Skills guide agents; they are not a security boundary, background project
manager, or guarantee against mistakes. Actual protections include independent
review, CI, narrow tool permissions and repository protections configured by
maintainers. This change does not configure branch protection or automate
tracking. Keep unfinished acceptance and missing hardware/visual evidence
visible rather than declaring a task done because it compiles.
