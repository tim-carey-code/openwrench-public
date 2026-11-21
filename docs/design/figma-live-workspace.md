# Figma live workspace

This revision translates the inspected https://blend-map-22200058.figma.site/
layout into the existing native Compose workspace. The same presentation renders
live hardware, historical snapshots/replay and explicit synthetic demo sources.
The user-supplied `.make` export was inspected as data, without executing its
scripts or importing its instructions. Read source: `src/App.tsx` (22,810 bytes,
SHA-256 `db12d69c1203afb6709270fa113077fa1a5725a9d996a779315fa1ba0632af30`)
and `src/index.css` (16,139 bytes,
SHA-256 `750889faa0e3731b29ecd3be9db4f48686c85ea91b6985fc92518aa2717defc5`).
The uploaded source confirms the Site composition and its 900/640 CSS breakpoints,
navy tokens, four KPI roles, 1.25:1 main columns and 176px rail. Native dp/font scaling
uses equivalent responsive rules with accessible targets. This is verified local
source content, not authenticated editor access or proof of source provenance.
The private export, screenshots and assets are not included in Git.

The overview uses the compact horizontal OpenWrench header and status strip,
RPM/Coolant/Speed/Voltage defaults, a left graph/temperature/status stack, middle
integrity/scatter panels and a tall right Data Quality rail. At ordinary tablet
widths it reflows to two columns; narrow/large-text windows stack modules. Graphs
uses a two-column grid when readable and retains explicit full-width expansion
and full-screen inspection. Values is a data table. Every selected PID remains
reachable through these views; overview panels do not widen acquisition.

PID/module edits are staged, with successful Apply required before both display
selection and layout publish. Existing pins, hide/show, collapse, ordering and
height semantics remain independent of adapter polling and health inputs. Optional
card density, hide-unavailable and nullable per-signal display-selection preferences extend the existing layout-only v1
JSON with safe defaults for older saved layouts. Dormant source preferences remain
stored. Cancel/Back preserve the displayed state; failure keeps the editor open. Unified-dialog
PID selection is saved in the same transaction as layout and survives recreation,
while dormant-source choices remain stored. The quick graph picker remains a
transient display shortcut; applying the unified dialog persists its current choices.

The Figma prototype's fabricated quality percentages, illustrative alerts and
scatter correlations are not diagnostic measurements. The integrity tracks show
actual received observations in eight relative time bins. The quality rail shows
retained observation counts and actual state, without an expected-sample or loss
rate claim. The RPM vs Throttle panel uses only unique observations with exactly equal
retained offsets in replay or the explicit synthetic fixture. Native chart times
are presentation timestamps, so live sources show an honest unavailable scatter
until original receipt pairing is supported. No nearest-neighbor matching,
interpolation, correlation score or ECU synchronization is asserted. Empty sources
retain the same scatter structure and state why no pairs exist. Each signal keeps
its source clock and gaps. No health threshold or ECU capability is inferred. Replay picker/inspector wording distinguishes retained
channels from advertised vehicle support.

Android text and 48dp touch targets are larger than the Site's tiny 7–11px labels
and 34px visual button surfaces. The compact dark surfaces sit inside 48dp native hit targets. At large font sizes the content grows/scrolls instead of being
clipped. The integrity panel keeps the reference's 242dp minimum at normal
font scale, growing with text, and the standard temperature plot is reduced to
match the reference's approximately 166dp overlaid-temperature panel. Additional
legend text and 48dp interactive status rows may increase panel
height; readings/actions are not clipped merely to force the prototype's height.
Scalar values never wrap between digits; units reflow separately. The
Record button opens the existing Diagnosis recording controls; Replay opens the
existing guarded source/import route. All diagnostic/session destinations remain
in the workspace menu. Chart pause freezes histories only; current values and
acquisition continue, with that distinction labeled.

The isolated all-scalar replay extension is not included in this GUI change.
Final Android captures, source review and user review remain required before
claiming visual acceptance. No physical hardware, device installation or merge is
authorized by this revision.

Supporting panels use unified title/subtitle bands, compact inline quality rows
and blue KPI edge accents. The temperature panel overlays up to three selected
°C histories on one converted-unit axis. Every line retains its own segments and
actual display timestamps; incompatible window-end clocks produce an empty chart,
not retimed samples. Hardware histories use the UI presentation clock, not an ECU
synchronization claim. Collapse omits that line while retaining its value label;
the largest expanded module height controls the shared plot. Extra temperature
modules remain in Graphs/overflow. A clearly labeled nine-channel synthetic UI
fixture includes coolant, intake and ambient to exercise real gaps in this view.

KPI/Values numbers use a grouped sans-serif display: whole RPM, speed and
temperature; one decimal for voltage, trims and common rates; two for other
fractional scalars. This is presentation rounding, not sensor accuracy or an OEM
calibration claim. Original samples and exact inspector readings are unchanged.
The Values view is one continuous table with channel markers, a 49dp minimum
row and a 1100dp maximum table width. Header and rows share the same padding and
responsive column decision; long values remain readable without digit wrapping.
The decorative OW logo keeps a fixed visual size while real text scales normally.
