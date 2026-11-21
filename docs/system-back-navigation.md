# Android Back navigation

Android system Back now unwinds the actual visited screens. Live Dashboard / Graphs / Values and Individual records / Drives / Scans / Cases participate in that same order. Selecting the current destination repeatedly adds no entry. At the first remaining visit, OpenWrench leaves Back to Android; it does not invent a home destination or trap exit. Recent history is bounded to 128 visits.

Dialogs, menus and the Android keyboard retain their normal dismissal priority. Signal details close before full-screen graphs. Full-screen entry is an overlay, and leaving it restores the actual previous display mode without adding a Graphs history entry. Paused charts, units and display preferences remain presentation state; navigating never starts or stops acquisition, recording, diagnosis or replay.

Each visit has a stable saved identity. Drive/comparison/case details opened during that visit consume Back before leaving it. A retained detail from an earlier visit does not outrank a newer page: Individual records → open drive → Advanced → Individual records → Back returns to Advanced. Returning further to the original visit restores its own detail Back handling. Pruned visits release their saved UI state.

Activity recreation preserves the navigation stack, consumed replay-selection token and current bounded display state. The retained replay selection redirects to Live Sensors only once; rotation or returning through Back must not redirect again. A process restart may reconstruct screen history from Android saved state, but does not reconnect hardware or manufacture a paused sample buffer.

Leaving a case page during asynchronous preparation/open/save invalidates late presentation delivery. An admitted atomic save still completes. Configuration recreation keeps the existing retained coordinator operation. No history schemas, hardware commands, source provenance or dashboard composition changed.

## Verification boundaries

Model/coordinator regressions cover visit ordering, deduplication, save/restore, replay selection, retained-detail visit identity, full-screen mode restoration and pending case cancellation. The offline-emulator capture exercises system key Back, menus, module Cancel, keyboard priority, full-screen/inspector, record tabs, rotation, root exit and a system edge swipe when the AOSP gestural overlay is available. These checks do not establish physical Meswao, TalkBack, installed-user upgrade or hardware qualification. See `docs/evidence/system-back/verification.md` for actual outcomes; a test added to the workflow is not evidence that it ran.
