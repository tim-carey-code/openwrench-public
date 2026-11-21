# Android GUI refresh

The native UI follows the supplied OBD Dash App PNG design references: dark navy navigation and canvas, slate bordered cards, cyan signal/status accents, blue selected controls, monospaced readings, and amber unavailable/partial states. Reference examples are never loaded as vehicle readings.

- Diagnosis retains its actual workflow and parked-operation gates. A phase ribbon marks the current workflow step, not successful scan outcomes. Context notices distinguish recording, paused, interrupted and historical evidence. Results retain Analysis, Charts and Evidence.
- Live Sensors defaults to Both. Both puts a compact segmented RPM gauge and speed/coolant cards above full-width chart tracks, then remaining values. Graphs contains only tracks; Values contains only cards, including unavailable signals. Every selected graph stays reachable in the same lazy vertical list.
- Cards reflow by available width and text scale. Labels wrap. Numeric readings, availability, receipt history, units and gaps come from existing presentation models. The RPM scale is a display scale, not a manufacturer limit.
- The single live toolbar shows labeled Both/Graphs/Values choices when they fit with the essential controls. Smaller or enlarged-text windows use the mode icon/menu. Full-screen exit and settings remain inline; overflow retains all applicable actions. Chart-only controls are absent in Values. Pausing charts leaves current values and acquisition unchanged.
- The PID drawer has search, wrapping checkboxes, scrollable choices and a fixed Done button. Display selection never changes adapter read requests. Back/outside dismissal preserves choices, just like Done.
- History preserves saved-record controls and status; Advanced uses two columns on wide windows and one on narrower/enlarged-text windows. Existing connection, import, export and acquisition routes remain intact.
- Compact replay status and its existing play/pause, restart, choose, stop and details actions are retained. Historical/synthetic origin remains visible. No fabricated seek/speed control was added.

## Deliberate differences from the static designs

The reference vehicle names, timestamps, measurements and findings are illustrative and are not copied into runtime. The application does not infer vehicle identity or success of missing scans. Existing workflows govern initial/final scans, connection setup, history and evidence; no mock actions or invented data are introduced. Advanced cards open the existing functional settings/tools rather than duplicating their state. The PID selector is a modal side drawer, not a simultaneous detail/editor layout. Chart time labels remain relative to actual receipt history because wall-clock alignment is not always available. Empty gaps remain empty, without invented interval annotations. Reference future concepts (presets, history search, same-vehicle comparison) are not implemented. Existing compact replay controls take precedence over the larger transport shown in the reference.

## Acceptance limits

Unit tests and hierarchy-checker self-tests are not Android visual or navigation evidence. See `evidence/android-gui-refresh/verification.md` for actual check outcomes and device limitations. No physical adapter, vehicle, user capture, uninstall, data clearing, migration or remote publication is involved.
