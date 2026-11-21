> Current disposition: #127 is closed/unmerged as superseded; original ancestry
> and equivalent behavior are preserved in canonical #129/#132. The ancestry
> statements below are historical QA evidence, not instructions to merge #127.
> Signing-key/old-Nick-history retention are not release gates; no data deletion is authorized.

# Pilot P2 fixes and verification checkpoint

This follow-up addresses the two P2 findings observed in actual API35 QA at
641d34ada973943cc61ca828bffe590366166a8a. Original screenshots, before/after
fictional data, memory telemetry and the proposed (unpublished) QA report remain
unchanged under /tmp/openwrench-pilot-qa/. No GitHub QA comment was retried.

## Change and regressions

Source revision a8011cad41c8c4b59a97ec53195bf7386edd0c12:

- Entry/correction views use their own remembered LazyListState, keyed by exact
  question, correction and captured form head. Newly opened forms no longer inherit
  the list/history position; same-view recreation can preserve scroll and draft.
- Manual values share a strict parse/finite check between form feedback and the
  record boundary. NaN, infinities, overflow and malformed numbers give:
  "Enter a finite manual value (for example, -16 or 0), or leave it blank if unknown."
  There is no coercion to zero or fabricated range/measurement qualification.
- Save attempts show that error both beside the manual-value field and immediately
  beside Save. Other save errors are also displayed beside the action. Invalid
  numeric input is not submitted; entered fields remain in the form. Correcting
  the value removes the numeric error and permits the normal validated save.
- Two new focused unit regressions cover malformed/nonfinite errors, exact draft
  values/attribution, absence of staged writes and unchanged committed bytes,
  blank/finite inputs, coordinator error propagation, and a subsequent valid save
  retaining author/reason/provenance and the frozen evidence prefix. Scroll geometry
  is an actual Android regression check, not a fabricated JVM visual test.

15 focused pilot cases passed. All required checks then passed serially on JDK17,
SDK35, one worker, in-process Kotlin, 768MB Gradle heap, offline/unsigned:

```
./kotlin/gradlew -p kotlin :shared:jvmTest :shared:testAndroidHostTest \
  :shared:assembleAndroidMain :androidApp:testDebugUnitTest \
  :androidApp:lintDebug :androidApp:assembleDebug \
  -PopenwrenchUnsigned=true -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs=-Xmx768m -Dorg.gradle.workers.max=1 \
  --no-daemon --offline --console=plain
```

173 shared JVM +168 Android-host +700 app tests = **1,041**, zero failures/errors/
skips. Lint: zero errors, eight existing warnings. Unsigned assembly and whitespace
checks passed. Logs: /tmp/openwrench-pilot-p2-focused.log and
/tmp/openwrench-pilot-p2-required.log, plus standard Gradle XML/HTML reports.
Independent read-only source review found both P2s addressed in code and no new
actionable findings at a8011cad; this is not an actual UI reassessment.

## Dependency reconciliation

No dependency or Nick branch was changed. PR132 remains stacked against exact
#129 e202fa2189d25508312cda4409388c4bcb5062b3, containing exact #131
74e33f41d318bcdcbc67da49da54dab2c707e85e. Main baseline remains
 ef53148592257fddc09097937a7f9e0d21e18498. The existing integration merge
fca6efad4d2ee778dc618114c8bde97573c362e3 is unchanged.

Historical checkpoint before reconciliation: #127 at 8ed01dad0a2b4a567473edca18724aea8b824de9 was NOT then an ancestor of #129/pilot,
but its workspace implementation overlaps #129. Exact source comparison confirms
InvestigationPresentation.kt and InvestigationStore.kt are byte-identical at
#127/#129; workspace differences add recommendation integration. Existing
question-reference and archive-sibling regressions are retained. Do not merge the
parallel #127 branch blindly or treat it as a second implementation to activate.
#127/#129 ownership/review disposition stays explicit.

Guided #124 at 30c537e61e5de6e0d9e3d3f96457201d1bdf2aac remains separate and is
NOT an ancestor or included dependency. This follow-up does not claim guided
assessment or its provisional warm policy is integrated. Nick #122/#123/#124 and
all original-checkout uncommitted work remain untouched.

## Bounded emulator reassessment attempt — still blocked

Exact-source unsigned APK SHA-256:
 a6a4af73b340410c98b02829de4499295aa354f1ef33ac40effdc724cff76ae6
Disposable-QA-signed copy SHA-256:
 c7081af39760c692bcaaf4a7f5131195c3b4e7926d9702af9aad05f99dbc06bd
The copy is /tmp/openwrench-pilot-p2-qa/openwrench-a8011ca-qa.apk. It was NOT
installed because the safety guard stopped the emulator before that step.

After all builds exited, host available RAM was about 6.3GiB with no swap. One
bounded attempt reused only ow_pilot_10dac37/emulator-5580, requesting
1,024MiB guest RAM, two cores, software graphics, no snapshots/audio/cameras.
The log explicitly reports "Increasing RAM size to 2560MB": this Google Play image
overrode the requested memory budget. The host floor
remained 3,072MiB. Available RAM fell to **2,998MiB**; the guard stopped only the
owned emulator immediately. No smaller/indefinite retries, unrelated process
termination, host setting change or physical-device operation followed.
Logs: /tmp/openwrench-pilot-p2-qa/emulator.log and memory.tsv.

Correction to earlier environment descriptions: both original emulator.log and
emulator-retry.log also report "Increasing RAM size to 2560MB". Their 1,536MiB
figure described the requested CLI setting, not the enforced guest allocation.
The original logs/screenshots remain untouched; actual observed memory minima
and previous functional observations are unchanged. Future checks must account
for this enforced allocation rather than assuming a lower CLI budget took effect.

The original two fictional results and deliberately injected eight-byte partial
pending fixture were not changed, moved or discarded. The proposed verified
quarantine of that known QA fixture was never reached. No fixed-APK navigation,
form-position, error-feedback, valid save, correction/history or reopen interaction
was observed. Original-head actual QA does not prove the fixes at this new source
revision. Both runtime P2 reassessments and broader UI acceptance remain open.

Next: when memory headroom is sufficient, reuse this exact APK and isolated setup,
verify/preserve the known injected fixture before moving it out of the staging
path, then rerun both repros and valid entry/correction/history/reopen. Never clear
unknown pending data or user histories to make a test pass. No new visual CI or
signing-key/installed-history retention release gates are introduced. Keep draft
pending exact-head independent review/CI and actual reassessment; do not merge.
