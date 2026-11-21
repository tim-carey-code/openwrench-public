# Disabled Android investigation IO boundary

Local-only follow-on based on reviewed retained-owner candidate
`62471109e69ecb2ba9bde22af00f7e9e73746ab8`. There is no application factory,
initializer, picker, manifest/provider component or UI constructor call. Nothing
is activated or published. All execution evidence uses fictional temporary/fake
resources; no real Android provider, installed data or private archive was read.

## Why this boundary exists

Existing snapshot/diagnosis picker exports in MainActivity use provider output
modes such as `w`/`wt`. They are not reused or changed. Android documents that
output-mode behavior depends on the provider, and an input/output open may return
null after provider failure. A generic selected destination does not establish
no-overwrite semantics. See [ContentResolver reference](https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri,%20java.lang.String)).

This candidate therefore supports explicit read-only content input and NEW
app-private export copies. Generic provider export always throws
UNSUPPORTED_PROVIDER **before opening any provider output**. No provider's
create-new, atomic rename or remote durability behavior is presumed.

## Callable, still disabled integration

The later UI must deliberately provide its application context, currently selected
content URI, opaque selection ID, retained owner scope and explicit cancellation
flags. This example describes future wiring; no runtime code calls it here:

```kotlin
val area = InvestigationPrivateArea(
    AndroidAppPrivateFiles(applicationContext), AndroidExclusiveInvestigationFiles())
val archive = AndroidRetainedArchiveSource(
    SelectedArchiveHandle(opaqueSelectionId, selectedUri.toString()),
    AndroidSelectedArchiveStreams(contentResolver), canceled = { sourceCanceled.get() })
val repository = RetainedInvestigationRepository(area.candidateRoot(), archive)
val owner = RetainedInvestigationService(repository, retainedScope)
// Dispatch CaseChoices, then Attach using the exact returned SelectedCaseRevision.
// After explicit inspection and export intent, on IO:
val session = area.newExport(newSessionId, observedOriginals) { exportCanceled.get() }
owner.dispatch(RetainedAction.ExportOriginals(observedOriginals, session))
// Render Rejected.ioFailure / destinations or Exported.destinations by typed status.
```

Source adapters never query a provider for discovery, acquire/reacquire a URI
permission or persist a grant. The UI must obtain any future access explicitly;
this task does not. An immutable SelectedArchiveHandle admits only a deliberately
supplied content URI; it has a redacted toString and no default source. URI values,
provider messages and raw archive bytes are not copied into error state or logs.
Opaque selection IDs must not contain VIN or other capture data.

Every source open is fresh. The stream wrapper owns closure, limits actual reads
to 25MB plus one overflow-detection byte, observes explicit cancellation, rejects
repeated zero-progress reads and preserves missing, access-denied/revoked, null,
read/close-failure, too-large and canceled failures as separate enum codes.
FileNotFound is a provider-reported missing/unopenable source, not proof of why it
is missing. The repository validates UTF8/case formats and records both exact
archive-byte token and canonical selected-case digest. Replacement makes a prior
selection stale even when only archive whitespace changed. Invalid/unsupported
archive content is sanitized to SOURCE_ARCHIVE_INVALID_OR_UNSUPPORTED, without
exposing enum/JSON input snippets. No source failure becomes empty/normal/sample
evidence. The retained origin, ECU-or-unknown and receipt rules are unchanged.

## App-private ownership and exclusive destinations

The explicit Android location adapter consults Context.filesDir only when called.
Its reserved child is `investigation-android-boundary-candidate-v1`; candidate
records live under `new-investigations` and the repository's versioned namespace.
The location helper merely returns a candidate path; it never constructs a
service or reads installed histories. Canonical-directory checks reject link
traversal/unavailable roots. No actual Context was supplied during this task.

Exports live in NEW `exports/<sessionId>` directories, with an immutable plan,
`files/<originalName>` data and separate `receipts` metadata. Separating these
namespaces supports legitimate originals such as `plan.json` without collisions.
The concrete Android file adapter uses Os.open with CREATE+EXCLUSIVE+NOFOLLOW,
owner-read/write mode0600, then descriptor sync and close. Os.open is available
from API21, below the app's min24; see [Android Os reference](https://developer.android.com/reference/android/system/Os#open(java.lang.String,%20int,%20int)).
Existing final-component files are not opened/truncated. Parent directories are
app-private and canonical; cross-process path replacement is not qualified.

Limits are ten export-session entries, at most20 originals and50MB total planned
data per session, bounded flat ASCII filenames, 32KB plan and1KB completion receipt
reads. Candidate valid ID filenames fit this contract. Invalid names/sizes fail
before data creation; no automatic rename, cleanup, eviction or overwrite exists.
Unknown unsupported names remain in original storage for a later explicit route.
Failed plans and partial files consume quota and are preserved.

## Receipts, failures and restart

The repository retains exact observed-original identities and hashes streamed
copies; this adapter independently verifies copied size/digest and successful data
flush/sync/close before a completion receipt. It propagates structured `ioFailure`
and destination receipts through the existing owner result. Export does not
repair or re-encode originals. Valid retained archives already omit VIN; blocked
opaque originals are lossless bytes, **not redacted**. A future UI must make that
privacy distinction explicit before exporting or sharing them.

- PLANNED: immutable source plan exists, no acknowledged destination stream.
- PARTIAL / CANCELED: bytes may exist; observed size/hash describe what is present.
- COMPLETE: this live session observed successful data and receipt sync/close.
- RECEIPT_FAILED: data may fully match, but receipt acknowledgment failed; no success.
- MATCHED_ACK_UNCONFIRMED: read-only reopen inspection finds matching data and a
  readable completion record. It does not prove the earlier receipt sync/close
  returned successfully, so inspection never upgrades this to COMPLETE.

`inspectExport(sessionId)` reads only that explicitly named session, with no
writes, grants, discovery, automatic retry, resume or discard. A copy with all
expected bytes but no valid receipt stays PARTIAL. Matching receipt bytes after a
lost sync/close acknowledgment stay MATCHED_ACK_UNCONFIRMED. Corrupt/partial plans
fail closed and remain preserved. Repeated open/session IDs fail rather than
replace prior data. Export cancellation is an explicit flag: detaching a frontend
still does not silently erase an admitted durable operation.

## What remains unqualified

Actual ContentResolver/Os/provider calls and Android filesystem behavior have
not been exercised. JVM fake exclusive creation models CREATE_NEW, not hardware
or provider qualification. A provider may block inside open/read; byte bounds and
cooperative cancellation do not establish a latency bound or force a blocked
provider to return. Provider-local/offline behavior, cancellation-signal integration,
URI grant lifetime and UI error handling need separate acceptance before use.
There is no new HTTP/runtime network dependency, but content-provider internals
are not authenticated or assumed local by this adapter.

Generic SAF/provider destination export remains unsupported. A later safe path
could expose an explicitly accepted completed app-private copy through read-only
sharing, or implement a provider-specific no-overwrite contract after review.
No FileProvider/share/grant/picker flow is added now. Production construction,
privacy/recovery UX, Android interaction/min24 verification, directory fsync and
power-loss behavior remain gates; signing/history retention is not reinstated.
No installed data, device, vehicle, actuator, GPT service, emulator or visual CI
was used. Publication and production activation are not authorized.

## Local verification

JDK17/SDK35, offline, one worker, in-process Kotlin, 512MiB heap:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$ANDROID_HOME \
./kotlin/gradlew -p kotlin :androidApp:testDebugUnitTest \
  --tests '*AndroidInvestigationIoTest' --tests '*RetainedInvestigationCandidateTest' \
  --tests '*FixturePreviewIntegrationTest' --tests '*CombinedRecommendationPreviewTest' \
  -PopenwrenchUnsigned=true -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs=-Xmx512m -Dorg.gradle.workers.max=1 \
  --no-daemon --offline --console=plain
```

69 tests passed:17 new Android-boundary,20 retained-candidate,15 fixture-integration
and17 pure-preview; zero failures/errors/skips. Log
`/tmp/openwrench-android-io-final.log`; app unit XML/HTML reports in this worktree.
Earlier compile/candidate compatibility and initial boundary runs also passed.
No aggregate shared/app suites, lint, APK assembly, actual Android/framework/provider
execution, URI grants, UI or power-loss checks were run. Main/test Kotlin compiled
as a dependency of focused tests; that is not Android storage/provider acceptance.

Independent source review during development identified raw source-decoder error
leakage and ambiguous receipt-close acknowledgment. Both were addressed with
sanitized structured errors and MATCHED_ACK_UNCONFIRMED reopen semantics. Tests
cover distinctive private sentinels in malformed/unknown-enum data (including
Attach), receipt sync/close failures with valid bytes, partial writes, cancellation,
source replacement and incomplete/empty plans. Exact final-head review must confirm
these fixes; implementer tests are not an independent rerun. No publication is
permitted by this slice.
