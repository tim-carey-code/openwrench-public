# Investigation workspace / recommendation integration

> Current disposition: #129 is canonical; #127 is closed/unmerged as superseded.
> Historical verification sections below are not current activation instructions.
> Signing-key/old-Nick-history retention are not release gates; actual data must remain preserved.

This is a separate draft integration slice. It connects the existing workspace
(#127) to the deterministic recommendation API/catalog (#128), without changing
those PR branches or Nick's #122/#123/#124. No store/schema, command, transport,
GPT service, dependency, private capture or runtime Internet integration is added.

## Dependency design and review boundary

Verified main/base: `546c661d186f9d441e836fe6103d2c440e79ae6f`.
Both dependencies were still unmerged drafts when this branch was created:

- Workspace #127: `8ed01dad0a2b4a567473edca18724aea8b824de9`.
- Recommendation API #128: `2f01ca17a159f8439e3f5f0081c15c45d05c10c5`.

The isolated integration branch includes only their exact necessary changes via
cherry-picks of #127's three commits and #128's one commit. No original branch
was edited; no merge was performed. Their combined integration baseline is
`997e66c403bb7f9e3e41438dd42d440d66641fbc`. Review the integration-only diff
**from that baseline**, separately from each original dependency PR's review.
The PR against main necessarily also includes the unmerged dependencies; it is
not an alternative authorization to merge their work. After dependencies are
accepted, refresh the integration branch against reviewed main through normal
non-destructive coordination and re-review the resulting exact head. Do not
assume cherry-picked dependency ancestry will make a three-dot PR diff shrink
automatically. Keep all dependency, CI, visual and human review gates visible.

## Implemented flow

From **Advanced → Local drives and scans → Cases → Investigation workspace**,
open an investigation and choose **Suggested next questions**.

1. Explicitly select one retained case, whose historical scan/drive origins are
   shown. Choose **Preview next questions**. The coordinator reloads the saved
   journal at the expected head and runs the existing API on its IO dispatcher.
   It saves nothing, performs no acquisition and does not update an assessment.
2. Review the next useful question and further questions. The page shows the API's
   reason, competing explanations/outcomes, missing evidence, OEM prerequisites,
   limitations and source applicability. Historical/synthetic correction values,
   actual bank coverage and observed source remain visible. Missing Bank 2 is not
   presented as normal. Order reflects prerequisites, not causal probability.
3. Record explicitly owner-reported starting behavior or symptom/correction timing
   with author, provenance/time (or unknown) and revision reason. These are normal
   REPORTED ledger observations through catalog helpers, not verified categories
   inferred from narrative. The earlier symptom and later engine-running interval
   remain separate. Preview again after saving a revision.
4. Conflicting existing owner answers remain visible. A deliberate selection
   affects only this preview and retains all reports/provenance; no latest answer
   silently overwrites a conflicting earlier one. Re-preview without a selection
   restores the API's clarification behavior.
5. After adding observations and competing hypotheses through the existing forms,
   expand a question, review its prerequisites/limits, select at least two existing
   hypotheses, enter author/reason and choose **Record this question**. This stores
   a canonical ProposedTest only. The audit reason adds the selected retained case,
   preview journal revision, catalog version and selected starting/timing report
   IDs, including explicitly selected UNKNOWN answer IDs (or explicit
   none/unknown-or-conflicting markers when no answer was selected/resolved), preserving context without a
   schema change. User-entered reason is limited to 3,500 characters to leave
   room for these references. Missing OEM information remains missing.
6. Use **Record an attributed result** to enter the existing result/correction
   form. Its exact adopted question ID is retained and displayed by the workspace.
   Re-preview after saving to see active supporting/contradicting/inconclusive
   author judgments and unresolved evidence questions. No result executes a test,
   identifies a failed component or closes a causal branch automatically.

A purge click/acknowledgment/continuity is not sealing/flow proof, and pressure
decay alone cannot identify a leaking component. Normal results weaken only the
tested mode under the tested conditions. Unknown/intermittent behavior and OEM
applicability remain explicit. There is no execute button, actuator capability,
raw-command console or pathway from a catalog question to NativeSession.

## Consistency and lifecycle

`InvestigationRecommendationPreview` binds output to a journal head and selected
case and retains validated requested owner-answer IDs. This preserves explicit
UNKNOWN selections whose reasoning classification intentionally remains unresolved. A wrong case, unpreviewed question, blank author/reason, missing hypotheses,
stale disk head or duplicate in-flight action cannot write a question. Existing
expected-head store semantics remain authoritative. Successful ordinary mutation
invalidates the preview; computing another preview never mutates history.

The existing scope cancellation behavior is retained: read-only preview delivery
is canceled on disposal; an admitted atomic ledger write finishes off-main-thread.
Author, case selection and owner-report form state use Compose saved state. A
preview itself is ephemeral and must be recomputed after recreation. Question
adoption is always guarded by the new preview's exact revision/case, not restored
cached output. Adoption reason/hypothesis selections reset when the preview case,
head, question or explicit owner-answer selection changes. Runtime rotation/keyboard/Back behavior has not yet been inspected.

## Verification and remaining gates

`InvestigationRecommendationIntegrationTest` exercises the actual coordinator,
store and API with a fictional reported 2013 Legacy 3.6R cold-start/-16% scenario,
synthetic qualified Bank 1 evidence and unavailable Bank 2. It verifies byte-exact
read-only preview, case selection, owner-report persistence, canonical adoption,
audit references, duplicate admission, attributed inconclusive results and reopen,
conflicting answer selection → adoption → reopen with durable selected IDs, stale writes and rejection of blank reasons. These
are integration unit tests, not Android UI or physical-vehicle tests.

No emulator was launched because Tim's desktop is under memory pressure. All
checks use one worker, a bounded 768 MB Gradle heap and in-process Kotlin
compilation; no unrelated processes or system/swap settings are changed.
Phone/tablet, enlarged text, actual scrolling/touch targets, keyboard/Back,
rotation and end-to-end Android interactions remain **visually/runtime unverified**.
Tim/Nick's exact-head manual visual review,
dependency acceptance, current-head CI and human review remain gates. No merge is
authorized. No source/test success establishes hardware qualification.

Later work can improve presentation and add richer typed fault-mode/procedure
results only after the existing catalog/ledger boundary is explicitly designed.
This integration deliberately retains #128's limits: no verified normal mechanical
result, OEM procedure or automatic branch closure can be inferred from free text.


## Reconciliation after #128 merged

Main advanced to `77b9d6b6e616cb2687195db103022a34dd1a088c` by merging #128.
The authorized #129 reconciliation merges that main history into this feature
branch without rewriting any branch. The sole conflict was an add/add in the
reasoning guide's integration link; both the main API documentation and the
integration link are retained. The selected-report audit fix, including explicit
UNKNOWN selections, remains unchanged. #128 is no longer an unmerged dependency;
#127 was later closed/unmerged as superseded; canonical #129 preserves its
equivalent behavior and original ancestry. Do not merge it twice.

The owner-operated update verifier now includes every
`files/investigations-v1/*.json` journal in its before/after inventory. Mocked tests
verify matching bytes, changed investigation bytes and per-file hash failures,
using fake Android tools only. They do not install an APK or validate a real
signing key/history upgrade. A coordinator cancellation regression models disposal
while preview IO is pending, checks no late preview delivery and byte-preserved
history; it is not proof of Android Back/keyboard/rotation behavior.

One-time hands-on visual validation remains separate from CI. The available tool
inventory exposes no isolated remote Android runtime/controller; the attempted
cloud QA environment lacked SDK/emulator/images/KVM/display, while launching an
emulator on the desktop is explicitly prohibited by memory constraints. No safe
actual-app visual route has been established. No new visual workflow, dummy
passing status, browser mock acceptance, user-device install, uninstall or data
clear is performed. Tim/Nick's exact-head hands-on interaction remains separately unverified.
Signing-key/old-Nick-history retention are no longer release gates; no data loss
is authorized. Native and saved-drive CI keep their existing roles.
