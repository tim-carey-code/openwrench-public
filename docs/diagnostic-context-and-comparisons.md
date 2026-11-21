# Warning context and comparisons across saved drives

## Explicit warning-context scan

Diagnostics → **Scan with warning context** runs only while connected to hardware
with live polling stopped and no other operation reserved. It uses the existing
serialized session owner. The ordinary diagnostic scan and guided idle flow keep
their existing requests; monitoring never automatically requests these services.

The new scan gathers the supported Mode 01 snapshot, then standard code services
03 (stored), 07 (pending), and 0A (permanent), followed by supported Mode 02 frame
zero. NO DATA is unknown, not a successful empty code list. Exact negative replies
for unsupported service/subfunction or out-of-range requests remain unsupported;
conditions-not-correct remains unavailable. Other malformed, conflicting or
unexpected replies fault the session and retain the failed-release gate.

Freeze-frame support is read independently using `020000`. The trigger request
`020200` is sent only if advertised. A missing/zero trigger stops further frame
reads. Only advertised PIDs in the explicit first-block catalog are requested:
03, 04, 05, 06, 07, 08, 09, 0B, 0C, 0D, 0F, 10 and 11. Current Mode 01 support does not
substitute for frame support. Responses must match mode, PID, frame zero and
exact decoder length. Per-PID NO DATA/rejection remains visibly unavailable.
Other frames and later support blocks remain uncovered.

Every exchange keeps the existing three-second/4096-byte/prompt limits. There
are at most eighteen additional OBD requests (three code services, support,
trigger, thirteen measurements), plus bounded protocol/header setup. Cancellation
is checked before every further exchange; an interrupted operation publishes no
new partial snapshot. CAN replies use the selected ECU, or pin the sole observed
source for this operation; another ECU cannot replace it halfway through. When
headers were enabled temporarily they are restored before ordinary reads resume.
Legacy unaddressed replies must be single-source and retain unverified identity.

The report explains that pending codes do not identify a failed part and that
permanent codes do not necessarily indicate a currently present fault. ECU-stored
freeze frames have unknown capture time and are not automatically matched to this
drive, every returned code, or a warning bookmark. The captured report can be
copied or explicitly saved through existing scan history. VIN is omitted by
default. No clearing, control, enhanced-module probing or runtime Internet is
added.

## Qualified comparison evidence

Assessment pack 2 retains a typed comparison window beside a qualified trim
finding. At each actual LTFT receipt it records fresh RPM, calculated load and
coolant context. All three must be present throughout at least ten LTFT receipts
spanning thirty seconds. Missing, NO DATA or expired context starts a new
comparison interval; refreshed values cannot bridge an earlier gap. The existing
motion/fuel-control and six-second LTFT gap gates still apply.

Comparison bounds come from those qualified receipts, never whole-trip min/max
or a decimated chart. The first warning observation remains intact; a later
complete comparison window may accompany it if its context and correction
direction match. Both intervals retain their own receipt offsets. This adds no
polling requirements or requests.

History → Drives permits selecting two completed reports. The user must confirm
both came from the same vehicle. The app also requires matching observed ECU
response IDs and compatible assessment versions; the address alone is not a
vehicle identifier. A recurring fuel-correction cue is reported only when the
retained windows have the same motion/fuel-control state, overlapping RPM, load
and coolant bounds, the same correction direction, and the sustained screening
cue in both. Overlapping broad ranges do not prove equivalent operating histories.

Different or missing evidence does not establish improvement, deterioration or a
successful repair. Repeated generic ECU warnings are not assumed to be the same
fault. The comparison is a historical, user-requested view and copyable report;
it does not modify the original drives, merge sessions, infer a VIN, or resume
acquisition. It is cleared when its view closes or a drive is deleted; delayed
reads cannot reopen a closed comparison.

## Storage and compatibility

Drive archives now write `openwrench.drive-history/3` in the existing private
folder. Versions 1 and 2 remain readable. Older findings without typed operating
windows stay explicitly insufficient for same-condition comparison; their missing
evidence is not reconstructed. A separate descriptive review can compare the
original findings and whole-run ranges; see [offline review](fuel-trims-and-offline-review.md). Unknown versions, corruption, bounded file size, rotation,
atomic writes and deletion protection retain the existing store behavior. Older
APKs cannot read version 3 archives; use the updated APK to reopen them.

## References and verification

- [ELM327 datasheet, pages 31 and 34](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf):
  service identifiers, separate supported-PID discovery and counted CAN DTCs.
- [Snap-on OBD-II service modes](https://www.snapon.com/DiagnosticsManuals/PDL/7100/EG/Content/Common/SMX_Scanner/OBDII%20Service%20Modes.htm):
  pending/permanent status and freeze-frame interpretation.
- [AndrOBD protocol source](https://github.com/fr3ts0n/AndrOBD/blob/master/library/src/main/java/com/fr3ts0n/ecu/prot/obd/ObdProt.java):
  independently inspected for standard frame-ID framing and service-specific
  support discovery. No source code or runtime dependency was imported.

Synthetic transcripts cover legacy/CAN code states, unsupported/unavailable and
NO DATA, frame support independent of Mode 01, source substitution, wrong frame
identity/length and cancellation. Controller tests cover explicit admission,
no automatic extra requests, no partial publication and failed release. Comparison
tests cover same-vehicle confirmation, source/context/range mismatches, fresh
metadata windows, backward-compatible archives and close/delete races. These
checks do not qualify a physical adapter, vehicle or OEM diagnostic threshold.

The next increment uses assessment pack 3 and archive format 4; see
[contextual fuel-correction evidence policy](contextual-fuel-correction.md) for
paired STFT/LTFT intervals, retention, classification and version compatibility.
Older results and their original rule versions remain unchanged.
