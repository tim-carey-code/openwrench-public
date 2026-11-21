# Android migration Phase A: RPM session contract

Status: retained historical RPM-only contract and offline conformance corpus
from main revision `96d8e9a7a666f9d5f8fef40c6ba6aa630daca887`. This contract remains
available in `shared` and is tested without hardware. It is not the interface
implemented by the broader native multi-PID engine. Current Android behavior,
Bluetooth/USB integration and qualification gaps are documented in
[Native Android](android-native.md). The Python/HTTP/desktop implementations
mentioned below now exist only in Git history; the active project is Kotlin-only.

## Narrow public interface

`RpmSession` is a separate common Kotlin suspend interface, not a replacement for
`DiagnosticClient`, `HardwareHttpClient`, or any HTTP wire schema. Its entire
operation surface is `connect(deadline)`, `readRpm(session, deadline)`,
`disconnect(session, deadline)`, and an immutable `snapshot`. It accepts no PID,
parameter name, command, vehicle identity, VIN, DTC, or service intent. Selection
is explicit and fixed when a future owner is constructed; there is no factory in Phase A.
Suspension accommodates bounded platform I/O without adding a coroutine library.

One authoritative owner owns one explicitly selected adapter and serializes all
adapter I/O. Screens receive snapshots and submit intents to that owner; they
must not construct competing owners. Admit at most one connect or read, with zero
queued backlog; overlapping connect/read requests return `BUSY` while the session
is otherwise eligible. A duplicate connect while active also returns `BUSY`.
Disconnect and coordinator shutdown are teardown intents, not queued adapter
operations: their admission rules below take precedence over this busy rule.
Platform-exclusive ownership/locking remains an implementation and qualification
gate; the interface itself supplies no lock.

The owner creates a process-local opaque `ownerId` and increments positive
`generation` for every admitted connection attempt, including failed attempts.
Never reuse a handle across owner instances/process restarts. Generation or
sequence exhaustion must fail closed rather than wrap. Reads carry the expected
handle; a different owner or generation returns `STALE_SESSION` before I/O.
Normal `disconnect(session, deadline)` carries that same expected identity. The
owner must compare both owner and generation atomically with operation admission,
before changing state, invalidating observations, cancelling work, or starting
cleanup/I/O. A mismatch returns `STALE_SESSION` with no effects, even when busy,
closing, or faulted. A delayed disconnect from A therefore cannot close B or
invalidate B's capabilities/results. Validation must not race with a new connect.
After confirmed release, a duplicate disconnect succeeds without I/O only for
the most recently admitted identity; retain that identity privately while the
public disconnected snapshot has no session. A is still stale after B releases.
With no admitted connection attempt, any supplied identity is stale.

Admission and teardown have one atomic ordering point per owner. First compare a
supplied session with the latest admitted owner/generation: stale `readRpm` and
`disconnect` return `STALE_SESSION` even if another operation is busy or teardown
is underway. For a matching identity, an eligible connect/read may take the sole
work slot only while teardown has not been accepted. A matching disconnect takes
precedence over that slot: accepting it atomically enters `CLOSING`, invalidates
capabilities and observations, and bars new connect/read admission. This includes
a read already running or waiting to publish; disconnect never returns `BUSY`
merely because that read holds the work slot. If read admission wins the race, it
may start, but a later accepted disconnect cancels it and discards any unpublished
result. If disconnect wins, the read returns `NOT_CONNECTED` without I/O. A read
whose result committed before disconnect may return success, but its observation
ceases to belong to the snapshot as soon as `CLOSING` is accepted.

The owner requests cancellation of admitted work, waits within the bounded
cleanup deadline for its adapter worker to quiesce, then closes the adapter once;
it must not overlap cleanup I/O with a still-running command. A read interrupted
before publication returns `CANCELLED`, even if its underlying response arrives
later. Late completion cannot publish or restore capabilities. If worker stop or
close cannot be confirmed by the deadline, enter `FAULTED` with
`releaseConfirmed=false` and retain exclusive ownership; no new connect is
admitted. A matching disconnect while `CLOSING` joins the same teardown without
starting another close; it reports that teardown's result. If its own wait
deadline expires first, it returns `CLEANUP_UNCONFIRMED`; the owner remains
`FAULTED` and release unconfirmed when the bounded cleanup cannot confirm worker
stop and close. A matching disconnect may retry bounded release only once the
worker is quiescent. After confirmed release, the latest identity disconnects
idempotently without I/O. Coordinator shutdown applies the same barrier and cleanup rules
without a session identity, including during connect/discovery.

Unconditional `shutdown(deadline)` is a separate internal
`RpmSessionCoordinator` contract. Only the owning module's application/lifecycle
coordinator retains this authority; regular clients receive `RpmSession` only.
It is not inherited by or exposed through `RpmSession`. Shutdown invalidates any
current generation and performs bounded cleanup, including connect/discovery
before a caller has received an identity. It requires no expected session but
preserves the same deadline, release-confirmation, and exclusive-ownership gates.
Phase A adds no coordinator implementation, factory, or lifecycle wiring.

`DiagnosticSource` is reused without changing its API. Every observation carries
that explicit source, session identity, positive per-generation sequence, RPM,
unit, and completed-response host receipt time. Real ECU source identity remains
unknown because headers are disabled; adapter identity does not identify an ECU
or vehicle. Synthetic test peers use `SIMULATED`; future hardware results must
use `HARDWARE`. There is no fallback between sources.

`RpmClock` is injected by the authoritative owner: nonnegative, nondecreasing
monotonic milliseconds in one process-local time domain. `RpmDeadline` is an
absolute deadline in the same domain; equality is expired. Expired work sends
nothing. Read, connect/discovery, and cleanup must all be bounded; the owner must
cap caller deadlines using separately reviewed finite platform limits. Preserve
the Python reset's 10-second timeout floor when selecting the future ATZ bound;
do not let that floor extend an already admitted overall deadline silently.
Phase A specifies no default platform timeout or retry policy implementation.

Receipt time is neither ECU sampling time nor wall time and is never refreshed
by observing state. `RpmObservation.belongsTo` checks current session/source and
RPM capability authority only; it is **not a freshness/TTL check**. Consumers
must apply an explicit age policy before describing a retained value as fresh.
This contract introduces no cache or polling operation.

## State and failure semantics for a future implementation

| Event | Required behavior |
| --- | --- |
| Explicit connect from released disconnected state | New generation; `CONNECTING`, initialize; `DISCOVERING`, read `0100` and verify protocol; then `READY` with scoped capabilities |
| RPM unadvertised | `READY` may report the unsupported capability; `readRpm` returns `UNSUPPORTED_RPM` without a sensor request |
| Successful read | Remain ready; increment sequence once; return finite RPM in 0–16383.75 with unchanged provenance |
| Read outside ready | `NOT_CONNECTED`; stale handle is rejected before I/O; neither condition sends commands |
| I/O/framing/decoding failure or permission loss | Invalidate capabilities and values; bounded cleanup; `FAULTED` with typed error; no retry/reconnect/fallback |
| Matching-identity disconnect or coordinator shutdown/lifecycle cancellation | Preempt busy admission, atomically invalidate reads/results and enter `CLOSING`; cancel admitted work, wait for quiescence, then perform bounded cleanup |
| Stale disconnect (including an earlier generation of the same owner) | `STALE_SESSION`; no state, capability, observation, cancellation, cleanup, or I/O effects |
| Cleanup confirmed | `DISCONNECTED`, no session/capabilities, release confirmed |
| Cleanup fails or worker may still run | `FAULTED`, release unconfirmed, retain exclusive ownership; reconnect is rejected until explicit disconnect confirms release |
| Late completion after disconnect/fault/generation change | Discard; it cannot update current state, capabilities, or observations |

`RpmSessionError` distinguishes lifecycle, busy, unsupported RPM, timeout,
connection loss, transport failure, malformed response, adapter rejection,
unsupported adapter/protocol, permission, cancellation, and unconfirmed cleanup.
No raw frames, device paths, identifiers, or exception messages enter this public
error model. An operation error may remain the primary error when cleanup also
fails; `releaseConfirmed=false` is the separate authoritative cleanup gate.
`FAULTED` with confirmed release still requires an explicit disconnect to return
to disconnected before a new connect. Cancellation of a coroutine does not prove
physical release; no new owner may open a still-running worker's adapter.

The model constructors enforce local invariants (ready requires scoped
capabilities, other states cannot retain them, disconnected requires confirmed
release). They do not implement transitions or concurrency. The common tests
exercise data invariants, fake-clock boundaries, and disconnect authority using a
test-only controlled reference owner. Regression cases disconnect A, connect B,
then deliver A's delayed/repeated disconnect and a foreign-owner identity; B's
snapshot, capability/observation authority, and cleanup count remain unchanged.
They also check last-generation idempotency, separate coordinator shutdown, and
controlled busy/disconnect interleavings. These are contract examples, not a
production session manager or concurrency proof; Phase B must test atomic
admission, worker quiescence, and late intents on the actual owner.

## Exact intended engine boundary

Initialization is `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0`, `ATI` in that
order, once each. Then request `0100`, identify protocol with `ATDPN`, accept only
`7`/`A7`, and permit explicit `010C` only when PID 12 is advertised. Strict single
unaddressed responses, ASCII/prompt validation, and the 4096-byte response ceiling
remain required. Repeated/ambiguous responses fail closed even if identical.

**No discovery beyond the initial standard `0100` bitmap is needed for RPM.**
PID 12 is represented by mask `0x00100000`. The continuation bit is retained as
`nextUnqueriedRange=32` metadata and never grants permission to request `0120`.
Other advertised bits are descriptive only. `RpmCapabilities` stores a UInt
bitmap rather than a caller-owned mutable list; it exposes only RPM usability.

This intentionally narrows the default Python adapter's capability-chain
discovery. The fixture runner uses its existing `initial_range_only=True` option
to compare identical scope without modifying Python behavior. Protocol 7/A7
matches the existing hardware session's gate; it is not generic protocol
qualification. Existing broader Python/HTTP operations remain unchanged.
`NO DATA` is an `ADAPTER_REJECTED` failure for this RPM contract, matching the
strict legacy RPM read; it does not adopt the newer scalar API's per-PID
unavailable result. The future owner faults and cleans up on a failed read;
Python's low-level adapter only invalidates its capability cache and relies on
the caller/service for cleanup. These lifecycle differences are intentional
contract requirements, not changes to the reference implementation.

## Portable corpus and evidence limits

`fixtures/v1/native-rpm.json` uses schema `openwrench.rpm-conformance/1`, stable
case IDs, source revision/files, and a human-readable independent expectation
basis. The 40 cases are synthetic, with no private capture or real identity.
Requests and responses are uppercase hex byte strings, preserving CR, prompt,
echo, and whitespace. Exactly one response or tagged failure follows each write.
`setup=initialized` prepends the document's exact initialization transcript;
`setup=none` prepends nothing. Unknown operations/failure tags are rejected by
runners. Expected objects contain a value/result or typed error; RPM numbers are
finite, exact quarter-RPM values with zero tolerance. Bitmap sets and ordering
are exact. Failed capability discovery never implies support.

These are operation-level transcripts rather than a portable asynchronous
session scheduler. RPM-read cases include `0100` and the 7/A7 protocol gate
before the optional sensor read. Initialization, capability, and protocol cases
also exercise those components individually. A future native runner must drive
the corresponding engine components; end-to-end session ordering and lifecycle
need separate manager tests, without changing these expected bytes to fit it.

| Evidence | What runs now |
| --- | --- |
| Initialization, malformed/ambiguous response, bitmap, unsupported RPM, protocol, timeout/loss/transport, forbidden-write parity | Python runner executes the existing ELM327 client/adapter against all 40 transcripts, with exact request count, full consumption, ATZ floor, and final cleanup assertions |
| Independent numeric expectations | Kotlin JVM corpus tests compute `(256*A+B)/4` from positive payloads and compare capability bits, including continuation; expected values are never regenerated from an engine |
| Portable strict peer | Kotlin JVM peer consumes byte-exact transcripts and tagged events, rejects extra/missing/overlapping reads/writes and non-RPM operations; negative controls verify it fails on violations |
| Session data invariants | Common JVM/Android host tests check identity/source/generation isolation, cleanup state, invalid numeric/state data, and injected-clock deadline boundaries; test-only reference-owner cases cover stale disconnects, busy/disconnect interleavings, and separate coordinator shutdown |

Kotlin does **not** yet execute the ELM framing/decoder failure vectors through a
native engine. Reading a transcript through the test peer is corpus/harness
validation, not engine parity. Phase B must add a driver that invokes the actual
engine against this peer/corpus and the state machine above. Before a real
transport: test concurrent admission on the actual owner, duplicate connect,
cancellation at every phase, expired deadlines, late results, cleanup failure, permission/lifecycle
loss, generation/sequence exhaustion, and reconnection invalidation using the
fake clock. Platform deadlines, adapter locking, Android background behavior,
and physical qualification remain outstanding. No hardware was accessed.

## Local validation

Using JDK 17 and locked Python 3.12 dependencies:

- `uv run ruff format --check .`, `uv run ruff check .`, and
  `uv run mypy src tests`: passed.
- `uv run pytest -q`: 1,070 passed, including 42 new fixture/harness checks.
- From `kotlin/`, `./gradlew :shared:jvmTest :smoke:run :frontend:jvmTest
  :desktop:test :smoke:installDist --offline --no-daemon`: passed; shared JVM
  reports 54 tests, including four controlled busy/disconnect cases added for
  this finding. Frontend reports 97 tests and desktop three, none skipped.
- `:frontend:simulatorIntegrationTest`, `:frontend:hardwareIntegrationTest`, and
  `:frontend:recorderIntegrationTest` with this checkout and its locked Python
  environment: passed; 12 tests, none skipped. All transports were scripted or
  loopback simulator processes; no physical devices.
- Packaged smoke output compared with `uv run python kotlin/check_simulator_parity.py`:
  vehicle, PID report, all eleven typed simulator values/units passed.
- `./gradlew :shared:assembleAndroidMain :shared:testAndroidHostTest --offline --no-daemon`:
  blocked locally. Without SDK configuration, Gradle reports SDK location not
  found; with `ANDROID_HOME=$ANDROID_HOME`, it reports missing
  `android-35`. The installed SDK contains only platform 37 and build-tools
  36.0.0, not required platform 35/build-tools 35.0.0. It also emits an SDK XML
  version warning. Android compilation/host tests did not run locally; JVM
  success is not Android evidence. Existing CI installs the pinned SDK.

Passing these software checks does not qualify Android, Bluetooth, another
vehicle, PID, or recovery behavior. Stop at draft review; this contract does not
authorize Phase B.
