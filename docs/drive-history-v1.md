# Persistent local drive history

Hardware Live Health monitoring automatically saves a checkpoint at startup,
then when at least ten seconds of new receipt time have accumulated. It also
schedules the terminal snapshot immediately on Finish drive report, Stop live,
disconnect, a read failure, or leaving the foreground. The process-owned writer
survives Activity disposal. Serialization, file writes, and rotation run on IO
through a separate worker, not on the serial session owner. Storage failures
are displayed and do not fault the connection or issue vehicle requests.

History & Reports has separate Drives and Scans tabs. A saved drive reopens its
recap, recorded health assessment, bounded linked chart trace, and warning bookmarks with their
actual saved pre/post samples. It can be copied or explicitly deleted with
confirmation. A checkpoint from an earlier process is labeled recovered and
interrupted, never completed or live. Pending warning windows are frozen as
partial at the saved point. Reopening a drive does not advance receipt clocks,
resume acquisition, or reconnect an adapter.

Files use the versioned `openwrench.drive-history/3` format inside the app-private
`drive-history-v1` directory. Versions 1 and 2 archives remain readable. Version 1 has no recorded assessment;
version 2 retains its original assessment without newer typed operating windows.
Missing context is never reconstructed from old chart traces. Older APKs do not understand version 3 archives. No VIN, raw frames, device identifiers, account,
network connection, or new permissions are involved. Existing scan history is
not migrated or overwritten. The manifest disables automatic Android backup.
Uninstalling the app or clearing its data removes these local archives.

Each archive is limited to 2 MB. The store retains the latest 20 drives within
25 MB, automatically deleting the oldest known valid archives as necessary;
the drive currently being saved is retained even after a wall-clock adjustment.
The saved trace and events retain the live monitor's existing sample limits,
not every raw request for an unlimited drive. All archives explicitly remain
standard-OBD evidence, with the same module coverage limitations as Live Health.

Updates write and sync a temporary file, then replace the prior archive on the
same filesystem. A killed process cannot turn an incomplete temporary write
into a completed drive. The previous committed checkpoint remains authoritative;
known UUID-named stale temporary files are cleaned on startup. A process killed
before the next successful checkpoint can lose the newest unsaved observations.
The live screen displays saving/saved/failure status.

Unknown or corrupt archive formats are preserved. Rotation validates all planned
victims before any deletion. Failure invalidates cached metadata so retries
reconcile the actual files after a partial filesystem operation. Pending and
failed saves are bounded to 20 distinct runs; overload and disk errors are
visible, with retry and current-report copy available. A terminal update cannot
be replaced by an older checkpoint, and deletion cancels queued saves for that
archive. Saves do not run from synthetic demo or replay acquisition.
Disk results carry ordered catalog revisions, so delayed UI updates cannot
restore deleted rows or hide a newer saved drive.

The archive codec rejects duplicate keys, excessive nesting/size/counts, invalid
PID identity, non-finite values, invalid offsets, and mismatched event provenance.
Local synthetic tests cover round trips, reopening in a fresh store/coordinator,
incomplete temporary files, checkpoint recovery, count/byte rotation, corruption,
partial rotation failure, bounded retries, and delete/open races. Controller
tests verify automatic terminal saves and unchanged wire requests under storage
failure. Emulator checks validate historical recap/chart/event navigation.

This build only persists runs recorded with persistence enabled. Data that
existed solely in an older app process cannot be recovered after that process
or an APK update terminates it.

Qualified operating windows and the current comparison behavior are described in
[warning context and comparisons](diagnostic-context-and-comparisons.md).

Older drives also support [offline evidence review](fuel-trims-and-offline-review.md)
without reconnecting an adapter or recording a new drive.

The next increment uses assessment pack 3 and archive format 4; see
[contextual fuel-correction evidence policy](contextual-fuel-correction.md) for
paired STFT/LTFT intervals, retention, classification and version compatibility.
Older results and their original rule versions remain unchanged.
