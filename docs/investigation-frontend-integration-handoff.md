# Investigation backend handoff for Nick

Current local candidate: [disabled retained-investigation owner](retained-investigation-candidate.md),
based on reviewed local fixture wiring `5e45a3b0d910fc482da684fee0130c32ad646bbd`
and draft #134 pure preview `38c3ab58a799e6fc3f95545d76ea42a53f89b7da`.
This is one consolidated LOCAL-ONLY candidate, not another published PR or a
production authority activation. Tim/Nick own frontend design and activation.

Local Android follow-on: [disabled storage/source/export adapters](android-investigation-boundary.md)
provide explicit content reads and exclusive app-private copies. Generic provider
export remains unsupported; no grants, picker, app factory or runtime activation
are supplied.

Executable fictional service integration reference: [actions and observed StateFlow](retained-frontend-contract-example.md). It adds tests/docs only, without runtime activation.

## Authority and usable API inventory

| Surface | Current responsibility |
| --- | --- |
| InvestigationStore / investigations-v1 | Existing real workspace authority, ordinary schema1 journal; unchanged, no dual writes |
| DiagnosticCaseCodec / SavedInvestigation / receipt validators | Frozen VIN-omitted retained cases and exact evidence validation; original assessments preserved |
| ManualOutcomeContract | Reviewed frozen-prefix envelope; cannot interleave later questions/cases, not a general new authority |
| CombinedInvestigation / CombinedFixtureStore | Separate synthetic-only fixture contract; guards remain intact |
| CombinedRecommendationPreview | Pure case/context/revision-bound recommendation consumer; unsupported mappings explicit |
| CombinedFixtureCoordinator | Tested fixture-only preview delivery, byte/content tokens and context invalidation |
| RetainedInvestigation / codec | Disabled new-ID combined-log envelope; actual origins preserved, never authenticated by enum |
| RetainedInvestigationRepository | Disabled new namespace authority: list/open/create, fresh retained-case selection/attachment, append/correct, preview, recovery, original-byte export |
| RetainedInvestigationService | Explicit retained serialized IO owner, StateFlow loading/outcomes, one admission and canceled-delivery handling; no app factory |

Nick can connect to the candidate's typed action/state contract without a server.
The [candidate contract](retained-investigation-candidate.md) provides callable
examples, state/error behavior, exact source-revision binding, quotas, recovery
and export limits. Its new-ID namespace never reads installed histories or
migrates/overwrites old journals. No production/UI code references the candidate.
The fixture pilot is not promoted to real evidence or used as production storage.

The complete acceptance cycle is complaint → retained case → observations and
competing hypotheses → question A → attributed typed result/correction → new
case/question B → result B → context-bound preview → restart/reopen. Manual values
never qualify OBD intervals. Unknown/mixed/unverified sources remain visible;
no method result automatically establishes cause, normality or repair success.

## Canonical dependency path, without duplicate workspace merges

Main inspected at `ef53148592257fddc09097937a7f9e0d21e18498`. Canonical workspace
is #129 `2b0dcd3fd72a55d6141982338716c96a3756b6d8`, dependent pilot #132 is
`1e25dcbb9aed97a3478fd84eaad383a4582b56ab`; #131 storage remains its separate
proposal at `74e33f41d318bcdcbc67da49da54dab2c707e85e`. Combined fixture #133 is
`9581705264e82483a543c93031a3c082baec6b7c`; pure preview #134 is above. Drafts are
not assumed merged. No existing dependency or Nick branch is changed here.

**#127 is closed/unmerged as superseded**, not a live dependency to merge twice.
Its unique behavior was already preserved in canonical #129. Patch-equivalent
pairs: `95973e3=1b63864`, `c038da0=5ea2b10`, `8ed01da=ac08ba8`; original ancestry
was reconciled without changing the canonical workspace tree. Copied reasoning
`997e66c=2f01ca1` was also equivalent. Guided assessment #124 remains separate at
`30c537e61e5de6e0d9e3d3f96457201d1bdf2aac`; it is not included or a universal
warm-temperature rule. Nick's #122/#123 remain untouched.

## Gates that actually remain

Tim removed signing-key retention and old Nick installed-history preservation as
release acceptance gates. Do not reintroduce them through historical checklists.
This grants no permission to delete/overwrite user data, uninstall or access a
physical device. Existing immutable reports/provenance remain correctness
contracts; no automatic migration/data loss is part of this work.

Source tests/review are not Android UI or filesystem/power-loss qualification.
Nick still owns frontend design; actual interaction, accepted source/export IO,
production authority/root/lifetime, privacy/recovery UX and runtime activation
require separate review/authorization. One-time UI validation is distinct from CI;
no new visual CI is added. Existing historical screenshots/test evidence must not
be represented as a complete current-head runtime pass.

This local candidate is not published and cannot be merged/activated from this
handoff. Review the cohesive local result first, then decide the smallest safe
activation path; do not proliferate another sequence of partial draft PRs.
