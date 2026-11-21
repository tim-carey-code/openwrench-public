# Proposed next slice: retained evidence attachment and typed manual outcomes

**Design only — not implemented by the #129 reconciliation.** Tim/Nick own the
implementation scope and schema decision. Keep this independently reviewable from
UI acceptance and from the main-reconciliation/history-safety fix. Do not treat it
as permission for hardware, actuator commands, GPT, uploads or live procedures.

## Existing gap and reuse

The current ledger embeds frozen cases and can append extra case copies through
`InvestigationStore.append(..., additionalCases = ...)`, but the workspace does
not expose attachment of further evidence. `TestResult` preserves prose, author
judgments and supersession, but has no typed tested fault mode, method, criteria,
conditions or mechanical outcome. Catalog 1 therefore cannot turn “normal”, a
click or pressure decay into verified causal evidence. Keep that safety boundary.

The next bounded implementation should be **model/codec/store/API first**, using
synthetic fixtures and no UI redesign. Add the UI wiring in a later focused diff
after the contract is reviewed. Reuse existing case copies, receipt qualification,
source labels, optimistic expected-head writes, atomic saves and correction chains.
Do not introduce a second investigation store or a second qualification policy.

## A. Attach additional retained evidence deliberately

Proposed operation (names tentative):

```
attachRetainedCase(expectedHead, selectedSavedCaseId, ownerVehicleAssertion,
                   author, reason)
```

- Reload/validate the selected existing case on IO and retain its VIN-omitted
  immutable copy. No scan, recording, connection or transport request is started.
- Record the attached case ID and the owner's cross-case vehicle assertion with
  provenance. That assertion is not authenticated vehicle/ECU identity or proof
  of the same fault/event. Unknown/mismatched identity must remain explicit.
- Expose only actual available typed readings and existing qualified intervals.
  Preserve original drive/capture IDs, observed source, origin, bank, receipt
  offsets, conditions, policy versions and assessment/report semantics.
- Do not join short/interrupted intervals, separate sessions or drives to obtain
  qualification. Missing/unsupported/unadmitted bank evidence stays unavailable;
  charts, reported percentages and manual values cannot substitute for pairs.
- Duplicate case IDs cannot replace an earlier copy. Source-history deletion and
  rotation must not remove embedded evidence. Keep the current ten-case/25 MB
  bounds and fail without eviction or partial append when storage is full.
- A completed diagnosis can later supply a case through the existing explicit
  scan/drive selection and builder. Direct diagnosis-segment attachment is not
  implied by this first operation; it needs a separate retention/provenance design.

## B. Record a scoped typed manual outcome without certifying it

Proposed typed context/outcome fields, all linked to the exact recorded question:

| Field | Required meaning / restriction |
| --- | --- |
| Question reference | Existing ProposedTest revision ID; preserve its frozen wording. |
| Catalog reference | Explicit supported catalog ID/version, or custom/unknown; never derive a new identity from arbitrary prose. |
| Evidence context | Selected retained case/reference IDs and selected owner-answer IDs, including explicit unknown/unresolved status. |
| Tested fault mode | One bounded mode, e.g. sealing/flow under stated conditions; unknown is allowed. No whole-component or whole-vehicle health flag. |
| Method / evidence kind | Actual externally performed method as attributed by the author: measurement, observation, click/acknowledgment, continuity, document review or unknown. No executable procedure or raw-command field. |
| Declared outcome | Abnormal reported for this scope, normal reported for this scope, inconclusive, unavailable or not applicable. “Reported” is intrinsic, not an optional disclaimer. |
| Criteria source / applicability | Exact document/edition/location and attributed vehicle/engine/ECU applicability, or unknown. The app does not invent a specification or certify the source/applicability. |
| Conditions / timing | Actual reported circumstances, observation time or explicitly unknown, adaptation/reset history where relevant. New universal warm/pressure/MAF criteria are prohibited. |
| Measurements | Optional finite typed values with explicit units and origin/method. They remain manual/external evidence and can never qualify standard-OBD paired intervals. |
| Provenance / authorship | Who recorded it, source/observer, reference IDs, recording time distinct from observation time, reason and limits. No private-data upload. |
| Correction | Explicit same-question supersedes reference; preserve original result and its provenance. |

Keep two concepts separate: **author's declared result/effect** and the
algorithm's **ability to use it for this narrow question**. Unknown method,
criteria, applicability or conditions means an unresolved analysis gap; do not
erase or silently upgrade what the author reported. Structured fields alone are
not proof that a procedure happened or a mechanical result is true.

For the first supported scope, a click, acknowledgment or continuity must remain
non-discriminating for sealing/flow, even if the author reports “normal”. Pressure
decay can support a scoped observation but cannot identify a leaking component.
A reported normal result can weaken only the tested mode under the stated
conditions; intermittent/unknown behavior and competing hypotheses remain open.
No result should automatically declare a failed component, causal certainty,
probability, verified procedure or whole-branch repair success.

## Versioned persistence boundary

Typed context/outcome and attachment assertions do not fit schema 1's strict
step rows. Proposed approach: add an explicit `openwrench.investigation/2` format
for new typed steps, while continuing to read `/1` exactly.

- Reads never migrate, reevaluate, parse old prose into typed outcomes or overwrite
  old cases/assessments. A legacy result remains a legacy attributed result.
- A successful explicit new mutation can atomically write schema 2 in the existing
  investigation file/path. Keep previous bytes until validation, flush/fsync and
  rename succeed. Unknown/future/corrupt files block writes and remain preserved.
- Freeze original question/report text. A legacy catalog question may retain only
  its existing exact-template identity; custom/edited text is not upgraded by guess.
- Allow correction of a legacy result only with an explicit same-question link;
  preserve the original. Active-result handling must account for both old and
  new result types without double-counting or resurrecting superseded evidence.
- Old apps cannot read a new format: downgrade editing is unsupported. The
  [disabled retained candidate](retained-investigation-candidate.md) uses a separate
  new-ID namespace with no migration/dual writes. Signing-key and old-Nick-history
  retention are not release gates; preserve actual data without deletion.
- Keep existing quotas and source privacy; no additional store, runtime service,
  credential or acquisition owner. The read-only NativeSession allowlist remains
  authoritative and unchanged.

This format and the smallest initial tested fault-mode vocabulary require explicit
review before implementation. Do not add a permissive “verified” boolean as a
shortcut or treat an OEM reference as hardware/actuation authorization.

## C. Update the next question and keep repair verification separate

After a typed result, recompute from retained measurement evidence and attributed
manual scope. Explain what was supported/contradicted/inconclusive, what is still
unknown and which competing explanation the next question distinguishes. A
recorded question/result may advance the workflow without claiming a verified
mechanical cause. Missing prerequisites continue to be visible.

Maintain three independent repair-follow-up tracks:

1. Owner-reported symptom persistence/improvement/resolution or not rechecked,
   with occasion/provenance. Earlier cold-start difficulty is not measured by a
   later engine-running fuel-correction interval.
2. Fuel-correction comparison using existing compatible same-bank policy/source/
   context gates plus explicit cross-case vehicle confirmation. No reconstruction
   or arbitrary point comparison; trim improvement alone is not repair success.
3. Reported adaptation/reset history and unknowns. A changed/reset state limits
   interpretation; do not issue reset/clear commands or infer a reset from values.

## Acceptance tests for the implementation assignment

Use only fictional data based on the *reported* 2013 Legacy 3.6R starting/-16%
scenario. It is not a confirmed finding, a two-bank observation or a purge diagnosis.

- Attach/reopen/source-delete a second case without changing original copies,
  versions or source labels; reject duplicate replacement, stale head, wrong case,
  canceled delivery and quota/write failure without losing earlier bytes.
- Show actual measured negative Bank 1 separately from reported Bank 2; reject
  short/stale/deduplicated intervals, chart-derived pairs and cross-session joins.
- Round-trip every typed outcome, unknown method/conditions/applicability and
  explicit owner-answer selection. Manual data never becomes qualified OBD input.
- Cover click/acknowledgment/continuity versus real sealing/flow attribution,
  pressure-decay ambiguity, scoped reported normal/abnormal, intermittent and
  unavailable outcomes, conflicting results and legacy-to-typed corrections.
- Read schema 1 unchanged; reject malformed/future schema 2; test atomic migration
  failure/reload and no history erasure. Never install, uninstall or clear a user's
  app to make a test pass.
- Keep symptom, compatible correction and adaptation tracks independent, including
  apparent trim improvement with untested/persistent symptoms or a reported reset.
- Independently review exact-head code and run bounded serialized checks. Actual
  Android repeated actions, Back/cancel, keyboard, rotation, large text and visual
  acceptance need a safely isolated one-time hands-on environment; no new visual
  CI workflow or static-test substitute. No hardware, GPT or private captures.

**Next concrete action:** Tim/Nick review this schema/result-scope proposal; then
assign the model/codec/store slice with one supported manual fault mode and its
missing-evidence rules. UI expansion remains separately reviewable and does not
remove #129's outstanding hands-on acceptance gate.
