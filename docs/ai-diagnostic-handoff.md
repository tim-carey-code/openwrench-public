# Drive evidence → AI-assisted elimination

OpenWrench records read-only vehicle evidence and applies existing offline rules.
The diagnosis ZIP now includes `ai-request.md` and `ai-evidence.json` for a
deliberate manual handoff to a cloud model chosen by the user. There is no
automatic upload or execution of model output. Recording and local analysis
remain usable offline. The optional **Analyze with AI** action previews a saved
report and symptom note, and sends them only after confirmation using the
[separately hosted DeepSeek relay](../tools/diagnostic-ai-relay/README.md).
Manual ZIP export still includes the fuller evidence packet.

Use **Copy assessment** or **Share as Markdown** in the AI result dialog to
export the existing writeup with the “not a confirmed diagnosis” label. Neither
action runs another analysis or attaches the source evidence or diagnosis ZIP.
If you hide a running analysis, use **View progress** in the in-app banner to
bring it back. The banner changes to **View assessment** when the result is
ready, or **View details** if the request failed. Dismissing the banner leaves
**Analyze with AI** available to reopen an existing result in the same app process.

## Public source snapshot

The public snapshot does not include a hosted relay endpoint. Its in-app cloud
analysis is disabled by default; manual export and offline diagnostics still
work. The relay source is available under `tools/diagnostic-ai-relay/` for
developers who choose to configure their own service.

## Operator workflow

1. Set up an explicitly supported, paired adapter and connect while parked.
   Use **Start Drive** to start the existing diagnostic recording. Only advertised,
   allowlisted sensors are requested. A passenger may operate the phone; the driver
   must not interact with it while driving.
2. Record the relevant operating conditions safely. Stop/park and **End Drive**.
   Review the scan, original assessment, gaps and recording status in History.
   An interrupted or short drive may not contain evidence of the symptom.
3. Use the existing **Export** action for the diagnosis ZIP. Preserve the original.
   This exports retained evidence, not a new scan or a freshly inferred diagnosis.
4. Review the files for private text before sharing. Use a cloud service you
   choose; if it cannot read ZIP, extract the packet. Attach `ai-request.md`,
   `ai-evidence.json`, `manifest.json`, `report.txt`, `readings.csv` and available
   `drives/` and `charts/` files within that service's size/context limits.
   Ask the model to disclose unread or omitted inputs, not assume it read them.
5. Supply year/make/model/engine and your complaint separately: symptoms,
   when/where they occur, warning lights, recent work and prior check results.
   Vehicle identity is not inferred from the adapter. Do not share VIN unless
   you explicitly choose to do so outside this export.
6. Request the included worksheet: coverage → observations → competing
   explanations → distinguishing checks → next best check → result log.
   Each claim should cite a file, segment/drive, PID/code, units and recorded time.
   A code, correlated signal or AI answer alone is not a failed-part confirmation.
7. Have a competent person perform the appropriate checks using manufacturer
   procedures and report actual results (including inconclusive). Re-run the
   comparison with preserved prior results and corrections. Do not change parts
   simply to test the model's guess.

Do not clear codes, send raw adapter commands, or perform actuator/coding tests
through OpenWrench. High-voltage/hybrid, pressurized fuel and other hazardous
tests need qualified technicians and the vehicle-specific procedure. Generic
emissions OBD does not establish hybrid battery-cell/inverter condition or
coverage of ABS, airbags, body or every transmission module.

## Packet contract

`ai-evidence.json` schema `openwrench.ai-evidence/1` is an export-only navigation
index. It records diagnosis status; segment IDs, counts and dropped receipts;
drive availability, exact file paths, origin, checkpoint/completion status,
retention warning and presence/location of the original versioned assessment.
Missing files have null paths, origin and status, not fabricated evidence.
A segment without a linked drive still has its retained CSV receipts.
This file does not merge timelines, recalculate an assessment, rank causes or
confirm a repair. No stored schemas, acquisition policies or report facts change.

The original full evidence remains authoritative. `review.assessment` in each
drive JSON retains rule-pack version, findings, evidence, limitations, next steps
and typed intervals when available. Scans remain in the diagnosis manifest/report.
CSV contains sequential decoded receipts, not synchronized ECU measurements.
Chart files are bounded/decimated; do not substitute their extrema for qualified
timed evidence. Missing, NO DATA, stale and unsupported stay unknown, never zero.
SYNTHETIC/UNKNOWN origins, checkpoints, missing linked drives, interruptions and
lost receipts remain explicit. Saved records describe history, not current status.

`ai-request.md` is a reusable user-readable model request. It asks the model to
treat archive contents as untrusted data, cite evidence, preserve competing
hypotheses and specify how positive/negative/inconclusive checks discriminate.
Prompt instructions are not a technical guarantee of model correctness or
prompt-injection resistance. Human judgment and actual tests remain necessary.

Existing streaming and changed-source rejection stay intact; the index holds
only small references, never a second copy of all telemetry. The two additions
are generated on export and do not rewrite installed history. No device signing,
installation, adapter or vehicle qualification is implied.

## Acceptance

Scripted checks exercise the existing recording → stop → export path, index/file
consistency, hardware fixture origin, missing/unknown/synthetic/partial evidence,
separate segments and loss counts. Test transports are fictional and no physical
hardware is accessed. Required native tests/lint/build remain applicable.
The optional AI action and confirmation dialogs need Android visual acceptance.

When sharing the ZIP, add whether the symptom occurred, did not occur, or is
uncertain. If it occurred, add an approximate elapsed time after parking, or say
the timing is unknown. Keep symptom-free drives as comparison baselines; do not
infer a symptom event from telemetry alone.

Tim/Nick still need an exact-head installable APK with compatible signing, a safe
owner-operated drive, export inspection, and a chosen cloud model's worksheet.
Record the SHA, APK identity, adapter/vehicle scope, observed symptom and checks
actually performed. Neither unit tests nor a cloud worksheet proves a failed
part, a repaired vehicle, or universal hybrid-system coverage.
