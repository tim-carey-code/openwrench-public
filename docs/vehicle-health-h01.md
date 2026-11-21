# H01 shared Vehicle Health contracts

H01 adds portable, read-only contract types in `kotlin/shared`. It contains no
vehicle health rules, reference ranges, polling, transport calls, persistence,
network use, or UI integration. An evaluated rule means its declared inputs were
available; it does not mean the vehicle is healthy. No H01 code runs in the
current Android application.

## Reused definitions

`Observation` reuses `SignalDefinition`, `SignalValue`, and `SignalSupport` from
the shared signal catalog. The dashboard's existing `SignalAvailability` values
now live in `shared`; a frontend type alias preserves the existing source API.
The `SignalDefinition.unit` is the canonical unit of an observation. Callers
must normalize values and units before constructing one. A null value is
distinct from numeric zero and is required for every non-available state.

Each observation identifies hardware, simulator, or replay; its session
generation; the app's monotonic receipt time; and an optional *supplied* source
timestamp. Source timestamps are opaque strings and are never treated as ECU
sample time without source evidence. Quality reasons supplement availability.
`ageMillis` uses the same monotonic clock domain and rejects an earlier `now`.

## Evaluation boundary

`HealthRulePack` and `HealthRule` declare explicit applicability, versions,
documented provenance, and `InputRequirement` sample, window, and age bounds.
The pure `HealthEvaluator` invokes a rule only when the pack and rule explicitly
approve the context and all inputs meet their requirements. It rejects
disconnected hardware, foreign generations or sources, unsupported or unknown
signals, unavailable, not-sampled, invalid or stale receipts, missing samples,
and short windows. Samples with one signal ID must also agree on type, canonical
unit, PID, and module; differently identified sources cannot fill one window.
Coverage records those reasons separately from findings. Findings carry
severity, evidence confidence, structured evidence with receipt age,
explanation, limitations, and ordered next checks.

The evaluator does not invent a healthy state when it receives no findings.
It has no approved pack or active diagnoses in H01. Synthetic tests exercise
only the contract gate; their sample rule asserts no vehicle condition.

## H02 integration requirements

- Agree on a language-neutral contract and evidence provenance with both
  diagnostic and client owners before adding real rules.
- Normalize native decoder outputs and replay records at a single read-only
  boundary. Use the existing `NativeController` session owner and its generation
  and monotonic receipt clock; do not add a second reader or change cadence.
- Supply explicit source and connection state, canonical units, support status,
  and source timestamps only when actually present. Preserve replay's historical
  nature and its original simulated provenance separately in any future mapping.
- Define reviewed applicability and evidence sources for each versioned rule.
  Unknown vehicle or unapproved conditions must remain `NOT_APPLICABLE`.
- Add rule-specific synthetic fixtures and review of thresholds, sample windows,
  limitations, and user-facing wording before displaying findings.

Native Bluetooth and USB remain experimentally unqualified on physical
vehicles. H01 uses no adapter or vehicle.
