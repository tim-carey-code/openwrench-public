# Dormant manual-outcome sandbox persistence proposal

This is an implementation for independent review, **not runtime activation**.
Main's InvestigationStore continues to own investigations-v1 schema-1 histories.
No UI, coordinator, recommendation API, store factory, migration or app lifecycle
instantiates ManualOutcomeSandboxStore. It accepts an explicitly supplied sandbox
root and legacy text; it does not discover or write an installed history path.
Only fictional temporary directories are exercised by tests.

## Chosen proposal and tradeoff

Keep typed review envelopes in a separate manual-outcome-sandbox-v1 namespace.
Never replace a legacy file with the review envelope or relabel it schema 2.
Retain original schema-1 text exactly inside each envelope, including whitespace,
with all original cases and semantics. The typed tail reuses #130's strict codec,
references, quotas, provenance, correction chains and method discrimination.
The sandbox is preparatory storage support, not a second active diagnosis store.

A separate namespace protects old-app readability and avoids destructive in-place
migration. It **cannot** prevent an older app from editing its own schema-1 file
or hiding typed records from its UI. Every sandbox inspect/append/recovery requires
caller-supplied current schema-1 text matching the embedded bytes exactly, plus
matching investigation ID. Any difference, even whitespace, blocks rather than
rebases, forks, silently drops typed results or interprets an old revision as
current. The supplied text must eventually come from a fresh source read on IO;
this dormant class cannot certify that a caller's string is current.

Thus this is not a downgrade editing solution ready to activate. Tim/Nick and an
independent reviewer must choose the runtime authority and downgrade UX before
activation: a coordinated editing fence or a separately reviewed versioned owner
that old apps cannot edit. Old binaries cannot acquire a new fence retroactively.
If downgraded edits diverge, keep both originals and stop; no data-loss approval
is inferred. No automatic merge, fallback to schema 1 or deletion is implemented.

## Persistence and interruption contract

- A synchronized process-wide owner serializes sandbox operations across instances.
  Full committed UTF-8 byte SHA-256 tokens reject stale writers and repeated
  delivery. This is not an authenticated identity or cross-process lock.
- Create is explicit and accepts only a validated legacy seed. Append adds exactly
  one typed outcome after full validation. No API replaces the frozen prefix,
  attaches cases, adds questions, edits prose or drops typed results.
- Write to an exclusively created same-directory ID.pending file, flush/fsync,
  then rename to ID.json. Failed staging or publication leaves previous committed
  bytes and pending bytes intact. No finally cleanup erases an uncertain result.
- Inspect is read-only and exposes both committed and pending state. No pending
  file is ignored by append. The two observed digests are required for explicit
  recovery; recovery accepts only a validated initial empty-tail seed, exact
  duplicate committed snapshot, or single append extending the entire prior tail.
  It validates matching legacy bytes, then fsyncs again before rename.
- Partial, corrupt, oversized, future-format, wrong-ID, changed-source or invalid
  reference files block writes/recovery and remain byte-for-byte preserved. There
  is deliberately no discard, automatic repair or garbage collection operation.
  An unresolved partial write needs a later reviewed preservation/export decision;
  it is not permission to delete an author's record.
- Reads and writes are bounded by the existing 25 MB envelope limit. Up to ten
  investigation IDs share the namespace; pending-only seeds count against that
  quota. At most committed+pending copies exist per ID (up to 50 MB each pair),
  with no eviction. Unknown entries block new creation. Existing 500 combined
  revisions and reference/text quotas continue to apply.
- The file sync and same-directory rename protect modeled process interruption.
  Directory entries are not fsynced; abrupt power loss/filesystem behavior is
  unqualified. Tests of injected failures are not Android storage qualification.

## Remaining integration work and gates

#129 is the canonical workspace; #127 is closed/unmerged as superseded.
Actual Android interaction remains separately unverified; signing-key and
old-Nick-history retention are no longer release gates. No visual CI is added.

Before production writes: independently accept or revise authority/downgrade and
recovery design, source freshness/concurrency and combined storage quota policy.
The disabled [retained owner candidate](retained-investigation-candidate.md) now
provides a new-ID authority proposal with explicit IO/source/recovery/export
contracts; no production factory or activation is supplied. No user installation
or history migration occurs here.

The #130 contract freezes the legacy prefix: after typed outcomes begin, it
cannot append a new ProposedTest or retained case. That is a concrete boundary
for end-to-end use, not a finished workflow. A later reviewed combined revision
contract must preserve existing question/context references while allowing new
questions and evidence; do not rewrite the frozen prefix to make this fit.
Then add typed entry/correction UI and recommendation consumption against that
accepted owner, showing missing context, source labels and author assertions.
Keep symptom, compatible correction and adaptation/reset follow-up independent.
No hardware, actuator commands, GPT, runtime service or private captures are added.
