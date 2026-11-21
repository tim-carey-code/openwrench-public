# Attributed manual outcome contract (dormant review slice)

This slice adds a pure Kotlin model and codec for reviewing typed, attributed
manual outcomes. It does **not** activate a new investigation format, saved-data
migration, recommendation input, UI, or test runner. Main still reads and writes
`openwrench.investigation/1` through the unchanged store. The workspace in draft
PR #129 is a separate dependency for eventual UI integration; this contract does
not depend on it or change that branch. Its Android visual/interaction acceptance remains separate. Signing-key and old-Nick-history
retention are no longer release gates; no data deletion is authorized.

## Usable now

`ManualOutcomeContractCodec.readLegacy(text)` validates a schema-1 snapshot and
retains its exact text with an empty typed tail. Historic result prose is never
interpreted as a typed answer. `encode` and `decode` round-trip the distinct
**review envelope** `openwrench.investigation-outcome-contract/1` in memory. It is
not schema 2 or a runtime store export. The codec itself performs no file I/O. Calling
`legacySnapshot()` returns only the original schema-1 evidence and revisions;
typed values cannot enter OBD qualification or change original assessments.

The envelope contains exactly `schema`, `legacy_json` (a JSON string preserving
original whitespace/key order), and `outcomes`. Each outcome is a fixed 20-field
array in constructor order from `AttributedManualOutcome`; context is a fixed
8-field array in `ManualOutcomeContext` constructor order. Nested details,
selections, catalog references, measurements, effects and retained references
use fixed-length arrays too. Unknown keys, versions, enums, duplicate JSON keys,
wrong primitive types, missing/extra array fields and invalid references fail
closed. Existing 25 MB UTF-8, 500 total revisions, 32 references/effects/values,
4,000-character text and ID constraints are retained. Escaping `legacy_json`
adds overhead: a valid large legacy file can exceed the review-envelope limit;
encoding fails without truncation or overwriting anything.

Each typed revision records author, reason, record time, separate actual
observation time (or unknown), observer, actual external method, conditions,
criteria/document reference, reported applicability, origin and provenance.
The context selects an exact existing question, retained case, owner starting
timing and adaptation/reset report revisions (including explicit UNKNOWN or unresolved), manual
observation revisions and retained measurement references. No new questions are
created by this contract. An optional catalog binding must match the exact
version-1 vapor-sealing question. A custom question must explicitly leave that
binding absent, making sealing/flow assessment incomplete.

The only named fault mode is unwanted flow when expected sealed; UNKNOWN is
explicit. Numeric values are attributed manual values with their own units,
never PIDs, decoder samples or qualified intervals. Reported/manual observations
cannot masquerade as retained scan/interval references. Existing case/receipt
validation resolves actual evidence; the contract introduces no thresholds.
A reported -16% correction, missing bank, click or manual zero supplies no OBD
interval and no new finding. Fixtures are fictional and marked synthetic.

Declared normal/abnormal, inconclusive, unavailable, inapplicable and unknown
outcomes stay distinct. Explicit author effects are retained as assertions,
never reinterpreted as proven causation. Inconclusive/unavailable/inapplicable/
unknown outcomes cannot assert support or weakening. `scopeAssessment()` checks
metadata completeness and method discrimination only: even a complete manual
measurement is merely `SCOPED_AUTHOR_ASSERTION_ONLY`. It does not validate OEM
criteria, applicability, a mechanical result or the author's judgment. A click,
acknowledgment, continuity, document review or pressure decay alone is explicitly
non-discriminating for sealing/flow. Synthetic origin always stays synthetic.
Normal reports concern only the tested mode and conditions; intermittent
behavior and competing explanations remain open. Pressure decay does not
identify a leaking component. No component or repair-success verdict is given.

Typed corrections must explicitly supersede the currently active result of the
same exact question, whether legacy or typed. Sequence and ID validation covers
the combined trail. Prior results and their contexts remain intact. Different
questions about the same hypotheses maintain independent active result chains.

## Acceptance and activation boundary

All tests use fictional data and scripted/local in-memory logic. Tests cover
strict parsing, references, owner UNKNOWN selections, manual-vs-retained
separation, non-discriminating methods, explicit uncertainties, both question
chains, quota limits, legacy byte preservation and old-store rejection without
rewrite. These checks establish a contract, not Android UI acceptance, retained
signing-key compatibility or hardware qualification. No vehicle commands,
actuator support, OEM procedure, normal threshold, GPT service or network runtime
is added. No live test procedure is supplied for the reported 2013 Legacy 3.6R.

Before any activation, independently review these contracts and explicitly
choose the runtime persistence/downgrade strategy. A schema-2 design must reject
unsupported data without silently dropping typed revisions; decide on a
separate namespace or reviewed all-or-nothing migration, recovery, quota and
old-version behavior. This slice deliberately leaves that decision open. Never
write this review envelope into `investigations-v1` or call it a migrated history.

Subsequent focused slices should (1) activate the accepted persistence/codec
contract with retained-history/downgrade tests, (2) add typed offline entry and
correction UI with exact context attribution and repeated-action/Back/cancel
acceptance, (3) explicitly teach recommendations to consume scoped assertions
without treating them as verified causes, and (4) separately capture symptom,
comparable correction and adaptation/reset follow-up. Each activation needs
independent review; UI also needs one-time actual Android visual/interaction
validation in a safely isolated environment. Do not add visual CI to substitute
for that gate. Future vehicle control remains outside the read-only policy.

Dormant storage follow-on: [sandbox persistence and downgrade proposal](investigation-storage-safety.md). It adds isolated failure/recovery support for review, with no runtime activation or migration.
