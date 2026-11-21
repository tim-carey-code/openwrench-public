# Compact saved-drive replay status

The saved-drive banner now shows only source provenance, Not live, playing/paused state and elapsed/retained time, with a 48dp play/pause button and Replay options menu. Synthetic, recorded hardware and unknown sources remain distinct. Progress is relative to the retained segment, not an invented full-trip duration; the one-second end hold displays the segment's final time.

Replay options preserves Restart replay, Choose drive and Stop replay. Replay details contains the original archive date, source explanation, original receipt offset, loop count, retained bounds, end hold and no-adapter/no-new-evidence explanation. Restart still clears frozen chart presentation before restarting the existing replay player. Acquisition, storage, replay timing, imports and graph controls are unchanged.

Normal and full-screen views share this status UI. At narrow widths or larger font scales the source/status text reflows above the two controls; it is never ellipsized. The area has a 128dp maximum height and vertical scrolling so controls remain reachable in short windows. Icon buttons have tooltips and accessibility descriptions. Playback status is readable without announcing every timer tick automatically.

## Separate preview

The restored workspace lacks the signing key for the prior Toolbar Preview. The new `replayStatusPreview` build type uses `org.openwrench.android.replaystatuspreview`, launcher label **OpenWrench Replay Preview**, versionCode 7 and versionName `0.3.5-native-preview-replay-status-preview`. It is a separate app with separate storage, not an update to Toolbar Preview. Original production, Telemetry Preview and Toolbar Preview identities remain unchanged. No uninstall, migration or installation is performed. Import a saved-drive JSON through the existing demo chooser if needed; the inherited document-picker crash on some tablets remains unresolved.

See `docs/evidence/compact-replay-status/verification.md` for checks, source provenance and limitations.
