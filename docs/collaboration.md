# OpenWrench collaboration

## Telegram for coordination; GitHub for the project record

Tim and Nick use Telegram to discuss priorities, agree on scope, assign work,
resolve blockers, and hand off tasks. OpenClaw can recover available context,
summarize decisions, and track next actions. It does not grant approval, transfer
ownership, or assume access to anyone's private conversations. Tim and Nick own
project priorities, scope, assignments, implementation decisions, and issue closure.

Use GitHub issues for bugs, feature requests, or decisions worth tracking
outside chat. An issue is not required for every task. Use an existing issue
or PR rather than opening a duplicate. When there is an issue, use
`Refs #<issue>` in the PR; do not auto-close it.

## Start or resume work

Check current main and relevant source, tests, and open and closed issues/PRs
before repeating work. Read the relevant available decision or handoff; if a
source is unavailable, state the gap rather than assuming there is no prior work.
Agree on scope and ownership in Telegram (or the existing task thread when
working outside Telegram). For parallel work, agree on file/module boundaries.
A handoff needs acknowledgment from the incoming owner; silence is not consent.
If scope or ownership conflicts, ask the human owner and continue only with
uncontested work.

Keep handoffs short: owner, exact branch/head, what changed, evidence or blockers,
and the next step. Share meaningful changes in the task thread; no duplicate
status log or routine issue updates.

## Review and finish

Open a draft PR on an isolated feature branch. Use the PR for the durable record
of what changed, consequential decisions (summarized without private chat
details), exact-head checks and artifacts, review findings, and open blockers.
Link to existing evidence rather than copying it. Follow the independent review,
human merge approval, and exact-build UI signoff rules in [AGENTS.md](../AGENTS.md).
Record limitations; missing CI during the current local-validation period does
not by itself block readiness.

An assigned [issue scout](../.agents/skills/scout-openwrench-issue/SKILL.md) may
post one bounded report on its assigned issue, then stop for a human decision.
Other issue posting needs authorization. A report is not permission to implement,
and this workflow does not enable monitoring or automatic notifications.
Authorized PR collaboration remains separate.

Treat old issues and evidence as history, not an active queue. In particular,
do not revive the retired Python/HTTP/desktop architecture from older reports.
