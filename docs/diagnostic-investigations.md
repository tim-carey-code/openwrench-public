# Offline diagnostic investigation foundation — policy/schema 1

The foundation provides an investigation journal and app-private store. The
[offline workspace](investigation-workspace.md) now exposes complaint, evidence,
hypothesis, question and manual-result entry through Cases. Existing case reports
and Start/Finish Diagnosis continue unchanged. It does not depend on the provisional guided-screening draft
PR #124, and changes no Drive-home/navigation surfaces owned by #122/#123.

## Available now

`DiagnosticInvestigation` in shared code validates an ordered, append-only audit
trail. Each revision retains an ID, sequence, recording wall time, author, reason
and typed step. Recording time is not the measurement or fault-event time; sequence
establishes journal order even if the wall clock moves. Steps distinguish:

- A user-reported complaint, including unverified vehicle descriptions.
- Reported observations, manually observed evidence, retained scan readings and
  retained qualified fuel intervals. A report never becomes a measured finding.
- Competing explanations linked to observation IDs, without probabilities,
  numeric rankings or a failed-component verdict.
- A proposed question with at least two competing hypotheses and explicit
  distinguishing outcomes. This is descriptive text, not a procedure executor.
- Manually recorded results, provenance and reasoned SUPPORTS/WEAKENS/UNCHANGED
  judgments. Only RECORDED outcomes with non-reported observation references may
  support or weaken a hypothesis. These are author judgments, not automatic
  causal validation. Relevance of the observation still needs human review.
- INCONCLUSIVE, UNAVAILABLE and UNSUPPORTED outcomes, which cannot change a
  hypothesis's support. Actuator proposals can only receive UNSUPPORTED results.
- A manual repair/symptom follow-up with before/after observation references.
  Symptom resolution is reported, never machine-certified.

Corrections append a result explicitly superseding the active result for that
same test. Original results remain intact; `activeResults()` excludes superseded
ones. No revision, complaint, observation, hypothesis or frozen case is edited or
deleted. A changed hypothesis is another hypothesis entry; there is no automatic
hypothesis ranking or aggregation of conflicting results.

`SavedInvestigation` embeds validated, VIN-omitted copies of existing immutable
cases. Scan links require an eligible PID with an actual retained value. Fuel
links resolve the exact finding/interval indexes, preserving bank, policy,
receipt and context provenance. Missing/unavailable data and absent Bank 2
intervals fail validation, even when a narrative says both banks were involved.
Referenced values come from these typed records, never the descriptive text.
Manual observations keep explicit provenance but are not independently verified.
New cases can be attached with a revision; existing embedded cases cannot change.
Source-history deletion/rotation does not remove the embedded evidence.

For repair review, `compareRepairReview` checks only the two selected retained
intervals. It requires distinct completed drives, explicit same-vehicle
confirmation, hardware origins, matching known observed ECU source and assessment
pack/version, the same bank/evidence policy/motion/fuel state, and overlapping
recorded RPM/load/coolant ranges. A failed check returns comparison unavailable.
Even a compatible pair always returns repair status `NOT_ESTABLISHED`:
matching ranges do not establish equal histories, and trim improvement alone
proves neither repair success nor symptom resolution. This does not authenticate
hardware origin or establish vehicle identity. No reference ranges are invented.

## Persistence contract and integration

`InvestigationStore(root)` creates a separate `investigations-v1/<id>.json` file.
`create` accepts a complaint-only journal and frozen case evidence; `append`
requires the expected latest revision ID, a new revision and optional additional
cases. Recreate the store and call `load(id)` to reopen. Callers must perform I/O
on a background worker. A process-wide lock serializes recreated store instances;
this is a single-process Android contract, not cross-process locking.

`openwrench.investigation/1` strictly validates keys, primitive types, duplicate
keys, step lengths, references, sequence, enum and policy versions. Limits are
10 investigations, 25,000,000 UTF-8 bytes each, 500 revisions, 4,000 characters per
text field, 32 references/effects per step, and the existing case limits (10
embedded cases with their own 25 MB bound). Failed validation, stale revisions,
full storage, unknown schemas and corrupt existing files block mutation while
preserving prior bytes. Writes use a temporary file, flush/fsync, then rename;
there is no automatic eviction. No existing history format is migrated or
rewritten. Older apps do not expose these files; downgrade editing is unsupported.

The API intentionally anchors to existing case evidence rather than changing
diagnosis metadata. The workspace adds browsing and manual entry. Export/import,
automatic case construction and diagnosis-coordinator attachment remain future work.
No dependencies, runtime network, GPT/API service, credentials or server are added.
The native session command gate and all vehicle command allowlists are untouched.
Test descriptions and manual prose cannot invoke transports.

## Synthetic motivating scenario

Tests use the reported cold-start complaint on a reportedly 2013 Subaru Legacy
3.6R and roughly -16% corrections only as unverified narrative. Their retained
scan/interval measurements are invented fixtures, not Nick's captures. They do
not establish that vehicle's actual bank coverage, fault or causal explanation.
A purge click is not sealing/flow evidence. Even leakage would not automatically
explain the entire complaint. No later-model bulletin is applied. No private
VIN, capture or physical adapter is needed or accessed.

## Concrete follow-on slices for the milestone

1. **Offline investigation workspace (implemented, visual acceptance pending):** coordinate with Nick after accepted
   #122/#123 navigation decisions; add start-from-case, list/reopen, complaint,
   evidence detail, hypothesis editor, test question and result-entry screens.
   Surface source/bank/availability and the complete revision trail. Add
   accessible UI validation, process-restart and exact-APK visual acceptance.
2. **Evidence attachment from diagnosis:** integrate with the accepted #124
   policy only after verifying its merged state. Select/retain completed
   diagnosis segments and scans explicitly; preserve original policy versions,
   partial coverage, receipt timing and manual same-vehicle assertions. Do not
   treat a warm-screening milestone as cold-start evidence.
3. **Bounded offline diagnostic algorithm:** curate reviewed, vehicle-agnostic
   hypothesis/test-question templates with applicability and evidence gaps;
   separate measured facts from reported claims. Validate competing paths on
   synthetic cold-start/negative-trim scenarios, including missing Bank 2,
   inconclusive purge evidence and alternative fueling/measurement explanations.
   Establish that the next question distinguishes alternatives before suggesting
   it. No vehicle procedures or automatic certainty.
4. **Repair follow-up and portable review:** guide compatible before/after
   selection, record the reported intervention and independent symptom follow-up,
   expose unavailable comparisons, and add versioned privacy-aware export/import
   with strict round-trip, corruption and retention/upgrade tests. Trim movement
   alone remains insufficient.
5. **Optional assisted interpretation, separately authorized:** design explicit
   consent/privacy and offline fallback before any GPT/runtime integration.
   Retained evidence and revision provenance remain authoritative; generated
   explanations are proposals requiring review. No credentials or uploads now.

Actuator capability remains unsupported and unverified. Any future proposal is
separate from this read-only milestone and requires a specific policy decision,
exact vehicle/ECU/adapter capability evidence, documented preconditions, bounded
duration, stop/restoration behavior, independent tests and specific hardware
permission. Generic OBD Mode 08 is not general OEM bidirectional support. No
actuator/control/service command, raw console, DTC clearing or hardware experiment
is implemented or authorized here.
