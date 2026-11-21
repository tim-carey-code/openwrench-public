# Consistent tablet navigation

Refs #116 and the merged #117. Diagnosis, Live Sensors (the live diagnostics
destination), History, Advanced and nested Advanced pages share one sidebar.
Use **Expand sidebar** / **Collapse sidebar** above the destination icons to
choose icon-only or icon-plus-label navigation. Every destination, including
Live Sensors, follows the same choice. Changing it does not add a Back entry
or change the selected destination.

| Available width / max(font scale, 1) | Presentation |
| --- | --- |
| Below 700 dp | Existing phone navigation and workspace menu |
| At least 700 dp, collapsed (initial default) | 80 dp icon-only sidebar |
| At least 700 dp, expanded | 224 dp × max(font scale, 1), icons and wrapping labels |

The choice is owned above destination navigation with Android saved-instance
state. It survives destination/mode switches, Back, full screen, rotation and
temporary narrow-window layouts. Narrow layouts hide the sidebar without
changing the choice. A new task starts collapsed; this is not a disk-persisted
application setting. Text scaling increases the expanded sidebar width so
labels remain readable. Both toggle and destination targets are at least 48 dp;
accessible destination names and selected-state semantics remain available in
either mode. The navigation list scrolls when height is limited.

The existing blue wrench stays in the sidebar when present; otherwise Live
Sensors shows it in its header. Explicit full screen hides normal navigation,
and Back restores the chosen sidebar mode with the previous display mode.

Only Android presentation and its capture checks change. Acquisition, commands,
session owners, recording storage, package IDs and signing are unchanged.
There are no new dependencies.

## Regression checks

Run `node --test scripts/capture-navigation-consistency.test.mjs` for assertion
regressions. The optional local helper
`scripts/capture-navigation-consistency.mjs` describes the former offline emulator matrix:
both sidebar modes across all four destinations at 1280, 1000, 999 and 700 dp,
1.5× text and portrait; phone navigation at 699 and 390 dp; nested settings,
display modes, full screen and Back; actual orientation changes and narrow-window
round trips without force-stopping; and Back after toggling without a phantom
navigation visit. Checks cover measured rail bounds, visible labels, accessible
names, minimum touch targets, selection, branding and restored mode.
Screenshots still require pixel inspection for readability.

The older navigation evidence (not included in this public snapshot)
documents #117, not this toggle. For new changes, Tim or Nick records the tested head and APK using the
[manual review checklist](manual-visual-review.md). The matrix above no longer runs automatically. Emulator results do not qualify physical devices, TalkBack,
adapters or vehicles.
