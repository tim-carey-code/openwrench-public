# Diagnosis Drive home

The Drive home presents the existing diagnosis coordinator; it does not introduce a second
recording or adapter owner. Vehicle identity is not inferred from the illustration.

- **Connect Dongle** uses the selected adapter and the existing connection/permission flow.
  The remembered adapter is resolved by the existing startup inventory. Without a selected
  adapter, **Set up connection** opens Connection settings. Demo, snapshot and replay also
  require explicit setup/source selection; they are never silently replaced by hardware.
- A connected adapter offers **Connection settings**, not a connect/disconnect toggle.
  Connection changes are disabled during diagnosis transitions/recording or native busy,
  connecting or unconfirmed-stop states. Cleanup uncertainty routes to settings for the
  existing release recovery controls. Pending USB permission cannot open a second prompt.
- **Start Drive** invokes the existing diagnosis start and initial scan. **End Drive**
  invokes final evidence capture and persistence. Actual connection status remains separate
  from scan readiness; starting, pausing and finalizing have explicit progress text.
- **Session options & details** retains pause, same-vehicle-confirmed Continue, and offline
  End Drive for paused/interrupted evidence. New-drive, history, result tabs and export
  still use their existing handlers.
- Reading counts stay Long throughout presentation. The normal tablet layout retains its
  720dp content-width breakpoint; enlarged text uses effective width and the metric row
  wraps, preserving reachable scrolling content.

## Automated verification

DiagnosisDriveHomePresentationTest covers connect/setup routing, busy/cleanup/source guards,
scan status, repeated/cancelled attempts with mocked state, retained-session actions and
phone/tablet/font-scale boundaries. DiagnosisCoordinatorTest exercises paused and interrupted
offline finish with scripted transport and retained evidence. These tests do not operate
Android's permission UI or establish physical adapter behavior.

Run the required Native Android tests/lint/build and the separate saved-drive import/replay
workflow on the final PR head. The general visual workflow remains removed.

## Exact-APK manual acceptance

Follow [manual visual review](manual-visual-review.md), recording head, APK SHA-256, signer,
reviewer, device/API, viewport and font scale. Tim/Nick acceptance remains required.

- Phone portrait (390–430dp) and tablet landscape (1280×800), normal and 2× text:
  inspect actual pixels, long durations/counts, scrolling and touch targets.
- Compare the hero/actions and progress states with the supplied
  [Figma design](https://www.figma.com/design/uLyujxLGgAwxmqmktwWbUl).
  Do not copy illustrative vehicle/adapter identities or sample counts as real data.
- Selected/remembered adapter versus no selection; permission denial/cancel/retry,
  repeated taps and rotation while a USB permission request is pending.
- Not connected, ready, preparing, recording, pausing, finalizing, completed and interrupted.
  Check Session options Continue confirmation/Cancel and offline End Drive.
- Back, rotation, background return, History/reopen, analysis/charts/evidence, graph modes,
  export/import/replay and saved layout retention.
- Use explicit simulations or scripted test transports for automated QA. Record physical
  adapter, installed-history upgrade and TalkBack checks separately; untested is not passed.
