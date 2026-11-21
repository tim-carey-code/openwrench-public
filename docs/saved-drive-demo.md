# Loop a saved drive on the dashboard

Tap **Demo a saved drive** in the source strip, then choose a drive stored in
this app. Selecting a drive starts an explicitly labelled historical replay and
opens **Live sensors** (the existing dashboard destination). It does not connect
an adapter or start a diagnosis. The dashboard, graphs, values, signal picker,
and full-screen display all use the selected drive's retained chart receipts.

**Pause / Play**, **Restart**, **Choose drive** and **Stop replay** remain
available, including in full-screen mode. Restart clears a frozen graph view;
ordinary loops do not override a deliberate visual freeze. Choosing another
recording starts a new display view. Backgrounding pauses playback and requires
an explicit Play after returning. Rotation retains the process-owned playback.
No playback is restored or started automatically after process death.

## Use a drive from another installed OpenWrench app

Preview packages have independent private storage. They cannot read the regular
app's drives. In the regular app, open a saved diagnosis and use its existing
ZIP export. In the preview, choose **Demo a saved drive → Import drive /
diagnosis ZIP**, select that ZIP, then select one of the imported drives.
A direct OpenWrench drive-history JSON file is also accepted. Import is local and in memory: it does not add, replace, migrate, or delete history.
Choose **Analyze with AI** under an imported drive to preview that drive’s
historical report and your optional symptom note. Nothing is sent until you
confirm **Send**. The ZIP, full readings files and separate scans are not sent;
use the original diagnosis ZIP for the fuller manual AI handoff. Analysis does
not require replay or an adapter connection.
No recording is bundled, and fictional data is never substituted for an absent
or unusable user drive.

The diagnosis ZIP must contain retained `drives/<id>.json` files. A snapshot,
text report, CSV alone, or a drive with fewer than two distinct numeric receipt
times for at least one signal cannot supply timed playback. If an older app's
export does not contain retained drives, that file is insufficient. No invented
interpolation, fabricated repeated values or reconstructed receipt times are
used to make it play.

## What is replayed

- Only saved chart receipts, at their original monitor-relative offsets and
  spacing. History retains a bounded, potentially decimated chart segment,
  rather than every receipt from the complete trip. The replay starts at the
  first retained chart offset; the original offset remains visible.
- A labelled one-second end hold makes the final point visible before repeating.
  This is a playback control, not a new sample or claimed recorded interval.
- Numeric zero stays a real numeric value. Missing readings, null points,
  recorded chart breaks and freshness gaps stay gaps. Each new pass resets the
  visible trace; it never joins the end of the previous pass to the beginning.
- Numeric display values expire after five seconds on the recorded receipt
  timeline. The displayed age means **Since saved receipt on replay**, not ECU
  source age. No UTC observation timestamp or source age is inferred from the
  archive's wall-clock start/save metadata.
- Hardware, synthetic and unknown original-source metadata remain distinct.
  Legacy records without origin are labelled unknown, not inferred hardware.
  All playback is historical and not current vehicle evidence.

Disconnect hardware and finish any pending stop/release operation before
starting replay. The admission check runs again after file reading. Playback
cannot open a transport, feed the live controller, save a drive, or create a
scan/diagnosis. Storage and acquisition schemas/algorithms are unchanged.

## Import bounds

The selected stream is limited to 25,000,000 bytes, the ZIP's total expanded
content to 25,000,000 bytes, each drive to 2,000,000 bytes, and each archive to
20 drives / 100 entries. UTF-8 and existing drive schemas are validated.
Malformed/truncated/unsupported archives, duplicate IDs, ambiguous entries,
unsafe paths and inconsistent ZIP directories fail closed. Entries are never
extracted to disk. Imported files and saved history are left unchanged.

## Import picker recovery

Both import paths try an alternate Android picker only if the normal picker
fails synchronously to launch. If neither opens, the existing message area
explains how to check Files/Documents and retry. Cancellation preserves content;
a late read cannot replace a newer selection. See [import reliability and its
evidence limits](import-picker-reliability.md).
