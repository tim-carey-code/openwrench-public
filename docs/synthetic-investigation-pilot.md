# Fresh synthetic investigation pilot

This pilot makes typed outcome entry, explicit corrections, full history and
recommendation previews usable in a deliberately bounded fictional investigation.
Open **Advanced → Local drives and scans → Cases → Synthetic investigation pilot**.
Choose **Start fictional pilot** explicitly; simply opening the page writes nothing.

The seed is immutable/versioned and generated deterministically from fixed values:
a reported cold-start/approximately -16% story, an invented later qualified Bank 1
interval, missing qualified Bank 2 data, two competing hypotheses and two frozen
sealing/flow questions for distinct fictional occasions. This is not Nick's data,
not an actual vehicle observation and not a failed-component diagnosis. The seed
uses existing receipt qualification, not new thresholds or chart reconstruction.
All new outcome records have an enforced SYNTHETIC origin.

## Scope and data ownership

Pilot code reads/writes only its own new synthetic-investigation-pilot-v1 root,
using the reviewed sandbox mechanics beneath it. It never opens installed scan,
drive, case, diagnosis or investigation histories, imports data, migrates formats,
automatically deletes files or replaces existing histories. Ordinary host-app
history coordinators retain their pre-existing behavior outside this pilot.
The factory supplies the immutable seed rather than a mutable legacy-store copy;
there is no cross-store source-freshness or downgrade editing dependency here.
Changed seed/version or invalid/future data blocks instead of silently resetting.
There is one fixed investigation; no arbitrary complaint, case, hypothesis or
question editing, export/import or reset operation is added.

Typed entry records author, reason, source, method, fault-mode scope, declared
outcome, optional observer/method/conditions/criteria, reported applicability,
optional UTC observation time and one optional finite manual value/unit. Blank
optional metadata is unknown, not zero or normal. Starting/timing/adaptation
references remain frozen in the seed; they cannot be rewritten as test results.
Author effects on both hypotheses require a rationale for support/weakening;
unknown, inconclusive, unavailable and inapplicable outcomes allow only unchanged.

A repeated result must explicitly correct the active result of the same question.
Earlier attribution and context stay in full history. Both normal and history
views show exact question IDs and readable occasion/wording. Stale form/disk
heads reject writes. Repeated admission is blocked; reopening after uncertain
completion reveals what was durably saved. Back/cancel of an unsaved draft writes
nothing. Already admitted writes can finish after leaving; late presentation is
invalidated. The form retains primitive draft state over Compose recreation.

Pending data blocks appends and is visible. **Finish validated pending save**
requires the observed committed/pending digests; only a validated seed, duplicate
or exact single append can publish. Corrupt/partial data stays preserved and
blocked; there is no discard button. Reload exposes uncertain write state.
Synthetic origin is checked before publication, including recovery. Existing
25 MB/500-revision/reference bounds apply, with committed+pending copies; the
single fixed seed keeps this pilot below the sandbox's ten-ID ceiling.

## Recommendation integration

The typed adapter calls the existing recommendation API on the frozen seed only.
Manual values and author effects never become OBD readings, qualified intervals
or synthetic legacy TestResult steps. Active typed corrections are selected
separately from old results, keeping superseded assertions out of the preview.
Method/scope gaps lead to RESULT_SCOPE. Complete scoped metadata can motivate an
INPUT_PLAUSIBILITY question about the competing explanation, as a fictional
illustration only. No hypothesis is automatically closed, ranked by probability
or established as a cause. Click/acknowledgment, continuity and pressure decay
alone leave sealing/flow gaps. A normal report concerns only the tested mode and
conditions; intermittent behavior remains open. Symptom resolution, comparable
correction and adaptation/reset follow-up remain separate future work.

## Dependency reconciliation and acceptance

Pilot branch starts at exact #131 head 74e33f41d318bcdcbc67da49da54dab2c707e85e,
then merges exact #129 head e202fa2189d25508312cda4409388c4bcb5062b3 into this branch
only (baseline fca6efad4d2ee778dc618114c8bde97573c362e3). Main is
 ef53148592257fddc09097937a7f9e0d21e18498 with merged #130. No dependency branch,
Nick #122/#123/#124, or original-checkout edits were changed. Existing workspace,
reasoning and storage files are inherited rather than reimplemented; new pilot
files plus a Cases entry are the focused implementation delta from that baseline.
#129/#131 remain explicit review dependencies until their disposition is known.
The pilot does not extend schema 1 or activate general-purpose typed migration.

Use fictional temporary directories for model, codec, storage, form and coordinator
checks. Required tests/lint/build run serially with one worker and a bounded heap.
Actual Android interaction evidence is recorded separately against an exact
commit/APK; compilation and string tests are not screenshots or UI acceptance.
Tim authorized one disposable desktop emulator for this validation, subject to
memory headroom, no overlapping heavy builds, no existing AVD changes and no
physical-device access. No new visual CI is added.

Retaining Nick's signing key and installed history are NOT release gates under
Tim's updated instruction. No uninstall, data clear, physical installation or
user-data mutation is authorized or needed. This does not permit data loss.

After this pilot: disposition dependency reviews and actual interaction findings,
then design a combined revision owner allowing later questions/evidence without
rewriting past context. Add real-user entry only as a separate reviewed scope;
never silently promote fictional records. No vehicle/actuator commands, GPT/API,
network runtime, private captures or hardware acquisition are introduced.

### One-time desktop emulator attempt (2026-11-15)

Built source revision 10dac3720714eab40cacf5f08effad898c48103a, including exact
#129/#131 dependencies above. Required checks passed: 173 shared JVM + 168 shared
Android-host + 698 app tests = 1,039; final app tests/lint/unsigned build repeated
after the API24-safe UTC parser adjustment. Lint: 0 errors, 8 existing warnings.
Independent read-only source review found no actionable findings at that source
head. Logs: /tmp/openwrench-pilot-focused.log, /tmp/openwrench-pilot-required.log,
/tmp/openwrench-pilot-final.log; standard module XML/HTML reports.

Unsigned APK SHA-256:
 a1d83bc5df4b6661df13443029943b9c60938e676fc32216a7429c447021dc80
Disposable-QA-signed copy SHA-256:
 46422a302cc02b25d30b9c1909cefac42e15a22562e1fa9cf7b0df50dc0cd0db
The temporary QA key is unrelated to any installed app; no retained-key release
gate is imposed. No APK/key or private data is committed.

Tim explicitly authorized a desktop emulator, superseding the earlier emulator
prohibition, subject to memory safety. Read-only inspection found KVM usable,
API35 images and about 6,015 MiB available with no swap and no active Gradle build.
A single new disposable API35 x86_64 AVD was started with 1,536 MiB RAM/two cores,
software graphics, no snapshots, no window/audio/cameras. Existing AVDs were not
opened. Its writable files use a new ignored disk-backed directory rather than
RAM-backed /tmp:
<checkout>/kotlin/androidApp/build/pilot-qa-10dac37.

The AVD reached boot_completed=1, but available host RAM fell to 2,478 MiB, below
the explicitly chosen 3,072 MiB safety floor for this repeatedly crashing,
swap-free desktop. The task stopped only its own emulator-5580 immediately,
before APK installation or any app interaction. The new AVD files were preserved.
Emulator log: /tmp/openwrench-pilot-qa/emulator.log.

**Actual app visual/interaction acceptance remains unperformed.** No app
screenshots, navigation, typed entry, validation errors, correction/recommendation
views, Back/cancel/reopen or recovery UI were observed. Unit/source evidence for
those paths is not Android interaction evidence. No physical device, install,
uninstall, user data clear, existing AVD change, host setting change, unrelated
process termination or new visual CI occurred. Resume one-time interaction QA
only when safe memory headroom is available; do not infer acceptance from boot,
compilation or these fictional unit fixtures.
