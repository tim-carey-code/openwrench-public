# Compact phone destination dock

Ordinary compact layouts keep Diagnosis, Live Sensors, History and Advanced
available in an icon-only bottom dock, including Dashboard, Graphs and Values.
The dock uses the existing destination and Back handlers. Each destination has
its full accessible name, Tab role and selected state; the active icon has a
filled indicator.

At normal phone widths the dock is 56dp high: a 48dp touch row and 4dp padding
above and below. Text scaling does not add a visible label row. Windows narrower
than 208dp use complete two- or one-column rows to retain 48dp target widths.
The existing outer safeDrawingPadding reserves Android system insets once;
the 56dp content measurement excludes system navigation space.

The sidebar condition remains available width / max(font scale, 1) >= 700dp.
Tablet rail appearance and expansion state are unchanged. Explicit fullscreen hides both
dock and rail; leaving fullscreen restores the previous display mode and
appropriate navigation. No acquisition, recording, source or storage policy
changes.

## Verification

DashboardVisualStateTest covers compact target sizing, narrow reflow, the
existing sidebar/font-scale boundaries, and fullscreen hide/restore for all
display modes. These model tests do not certify Compose semantics or pixels.

Follow [manual visual review](manual-visual-review.md) on the exact Native Android
APK. Keep the general visual CI workflow removed. Check:

- Phone portrait at 320–430dp: Diagnosis, all three Live Sensors modes, History,
  Advanced and nested settings; four icons, one selected, no visible text labels.
- Normal and 2x text: 48dp targets, scrolling content, no bottom clipping or
  overlap with Android gesture/three-button navigation.
- Inspector, dialogs/keyboard, fullscreen exit and system Back precedence.
- Rotate phone/tablet and return: selected destination/mode and sidebar choice
  survive, no phantom Back step; full screen alone hides navigation.
- Screen reader names and selected Tab state; physical-device and TalkBack
  acceptance are separate from unit tests and replay emulator evidence.
