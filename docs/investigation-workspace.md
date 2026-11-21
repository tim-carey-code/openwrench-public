# Offline investigation workspace

> Current disposition: #129 is canonical; #127 is closed/unmerged as superseded.
> Historical verification sections below are not current activation instructions.
> Signing-key/old-Nick-history retention are not release gates; actual data must remain preserved.

Open **Advanced → Local drives and scans → Cases → Investigation workspace**.
The local-records page is titled **Individual records**. This
nested workspace reuses the existing dark panels and leaves the Drive-home,
phone dock and diagnosis coordinator unchanged. It depends on the merged ledger
foundation (#125), not draft guided screening #124 or Nick's #122/#123.

## Usable flow

1. Create a diagnostic case through the existing Cases workflow if none is saved.
   It requires a saved structured scan, completed drive and explicit same-vehicle
   confirmation. No new scan or connection is initiated by the workspace.
2. Choose **Start investigation**, enter a reported complaint and who is recording
   it, select a case, then save. Vehicle descriptions remain reported claims.
3. Choose **Add observation**. A reported claim stays unverified; a manual
   observation requires provenance/method/time (or explicitly unknown). Retained
   measured evidence is selected from actual available scan values or retained
   qualified fuel intervals. Its value, bank, source, evidence policy and recorded
   conditions are generated from the frozen typed record rather than entered
   as a new measurement. Missing Bank 2 or a missing scan reading is not offered.
4. Choose **Add hypothesis**, state an unproven explanation and link motivating
   observations. Add at least one competing hypothesis. No numeric ranking or
   failed-part verdict is generated.
5. Choose **Add distinguishing question**, select at least two hypotheses and
   describe what differing outcomes would mean and their limitations. Choose
   retained-evidence review or manual result recording. Neither runs a test nor
   supplies a vehicle procedure; actuator/control tests are not offered.
6. Choose **Record test result**, select the question, enter the actual result,
   provenance and outcome, select observations, and record a reasoned effect on
   one of that question's hypotheses. Supporting or weakening requires RECORDED
   and non-reported evidence. INCONCLUSIVE, UNAVAILABLE and UNSUPPORTED can only
   leave support unchanged. These are attributed author judgments, not automated
   causal verification. A correction replaces the active result and its effect;
   earlier effects remain in history. Multiple simultaneous hypothesis effects
   in one result are not yet exposed by this UI.
7. If that question already has an active result, the form explicitly says the
   new entry corrects it. The original remains in **Full revision history** with
   author, recording time and revision reason. The normal view shows active
   results. Each result explicitly identifies the question it answers by readable
   revision reference in both views, even when questions share hypotheses or
   result wording. **Inspect frozen case evidence** exposes the original report sections
   without reevaluation. Return to the list to reopen a saved investigation.

Every revision requires an author and reason. Recording wall time is not an ECU
measurement/fault-event timestamp. Existing case/drive/scan/diagnosis records are
unchanged. Imported and synthetic origins remain historical and explicit.

## Persistence and lifecycle

`InvestigationStore.list()` supplies validated browsing metadata and visible
per-file errors. A corrupt or unfinished archive remains on disk; it does not
hide valid investigations. Corrupt standalone case history prevents choosing a
new case but does not prevent reopening independent investigations that already
retain their evidence. No migration, automatic eviction or deletion is added.
The foundation's limits still apply: 10 investigations, 500 revisions per journal,
25 MB per investigation and at most 10 retained cases. Full storage blocks new
creation explicitly; a deletion/export workflow is a later slice.

`InvestigationCoordinator` serializes each workspace's operations away from the
main thread. Saves use the exact expected revision ID, so a double submission or
stale writer cannot overwrite newer history. An admitted atomic mutation finishes
on IO if navigation disposes the page, while canceled delivery cannot reopen it.
The durable record is reloaded on the next visit. A saved-state creation token
makes retrying an uncertain initial save reopen the same record, including when
the original case has since been removed, rather than creating a duplicate. Open record, form fields and
selections use Compose saved state across ordinary activity recreation; unsaved
forms are canceled when deliberately leaving the workspace. Process recovery of
saved records relies on the list, not an in-memory owner or adapter session.

No new transport, command, permission, dependency, runtime network, GPT service,
credential or hardware access is involved. Review and copying of already saved
case sections retains their original assessment semantics. A purge click is not
sealing/flow evidence; even leakage does not establish the full causal account.

## Synthetic checks and visual gate

`InvestigationWorkspaceTest` covers actual negative retained Bank 1 evidence,
missing Bank 2, negative values/source labels, coordinator create/append/reopen,
source-case removal, double submission, external stale writes, invalid evidence,
corrupt history, canceled delivery and retained result corrections. It creates
fictional emulator input in the ignored `build/reports/investigation-workspace-fixture`
directory; no private capture/VIN is used. The narrative mentions a reported
2013 Legacy 3.6R cold-start complaint, but the invented paired interval is warm
and does not qualify cold-start behavior. No later-model bulletin is applied.

Local emulator visual testing was interrupted by desktop disconnections and
memory pressure before an APK was installed or a workspace screen inspected.
Tim requested no further emulator launch. Therefore actual UI interactions,
phone/tablet layouts, 2× text, keyboard/Back, rotation and process-restart UI
behavior remain **not visually verified**. Unit tests and compilation do not
substitute for them. Tim/Nick's [exact-head manual visual review](manual-visual-review.md)
remains a separate interaction gate. Signing-key and old-Nick-history retention
are no longer release gates; user-data deletion is still prohibited. No automatic
visual CI is added, and no physical adapter was accessed.

## Next focused slices

- Complete exact-head phone/tablet visual acceptance and the create → evidence →
  hypotheses → question → result → correction → reopen UI walkthrough when an
  emulator/device environment is available without destabilizing the desktop.
- Add further-case attachment, repair/symptom follow-up UI and privacy-aware
  export/import/deletion. Ledger repair status remains NOT_ESTABLISHED; trim
  movement alone cannot prove repair or symptom resolution.
- Integrate diagnosis segment attachment only after checking the accepted state
  of #124 and coordinating Nick's UI. Preserve partial coverage and policy
  versions; warm screening is not cold-ignition evidence.
- Curate independently reviewed offline hypothesis/question templates with
  applicability and evidence gaps. The present workspace is manual, not an
  automatic fault-narrowing algorithm or a generic possible-parts list.

Optional future assisted interpretation and actuator capability remain outside
this slice. Existing read-only command gates stay authoritative.

The separate [draft recommendation integration](investigation-recommendation-integration.md)
connects these layers through explicit case/head-bound previews and deliberate
question adoption. It retains all dependency and exact-head visual/review gates.
