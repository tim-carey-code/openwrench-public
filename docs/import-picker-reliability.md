# Import picker reliability

Snapshot import and **Demo a saved drive → Import drive / diagnosis ZIP** first
launch `ACTION_OPEN_DOCUMENT`. A synchronous `RuntimeException` triggers one
`ACTION_GET_CONTENT` attempt. If both fail, the existing message area explains
how to check that Files/Documents is enabled and up to date, check device
restrictions, and retry. No permission request or automatic selection is added.

The alternate contract preserves AndroidX OpenDocument's `*/*` type, MIME extras,
single selection and result parsing, and adds `CATEGORY_OPENABLE`. Snapshot
acceptance remains JSON/plain text; saved drives also accept ZIP,
x-zip-compressed and octet-stream. Both routes immediately consume and close the
stream using the same strict codecs and limits: 1 MiB snapshot/timed text,
2 MB per drive, 25 MB ZIP input/expanded data, 20 drives and 100 ZIP entries.
There is no retained URI grant, broad storage permission, network dependency,
archive extraction or saved-history write.

Null/cancelled results remain a no-op, never a fallback trigger. Launch failure
preserves current recording and imported content. Existing background policy
can pause playback when the picker opens; returning does not auto-resume it.
Original Activity Result registrations stay unconditional and in their original
order, with unconditional fallback registrations appended.

A process-owned main-thread request gate prevents an old snapshot/drive read
from publishing over a newer selection. Source/scenario changes, replay stop,
chooser close and explicit Connect invalidate pending publication. Beginning or
cancelling a request clears stale drive loading state; a superseded completion
cannot clear a newer request's loading state. Admission is rechecked before
publication. The gate retains no Activity or URI and does not survive process
death. It does not cancel an already running bounded read.

`ImportFileReader` shares immediate stream ownership and existing bounds/strict
UTF-8 decoding. It does not loosen ZIP or JSON validation. Launch warnings log
only route and exception class, without exception messages/stacks, URIs,
filenames, VIN or telemetry.

## Evidence limits

The confirmed code defect was that both launch calls could throw before the
result callback's exception boundary. The reported tablet cause is unconfirmed:
no tablet exception/version was captured and the crash was not reproduced.
A guard cannot detect or repair a picker that launches successfully and then
freezes/crashes in its own process. Cancellation is not evidence of a bad picker.

JVM regressions cover synthetic launch/null-result policy, successful synthetic
snapshot/drive JSON and diagnosis-style ZIP parsing, malformed/interrupted/denied
reads, stream closure, saved-history bytes and delayed publication. These are
shared-reader/gate tests, not Android Activity/provider tests. Actual picker
resolution/selection, Back/cancel/retry, error readability, rotation with an open
picker, process recreation and background/return require Android qualification.
Automated tests do not alter tablet settings or access vehicle hardware.
