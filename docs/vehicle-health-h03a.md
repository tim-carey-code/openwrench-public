# H03A health evaluation engine hardening

H03A hardens the existing pure Kotlin `HealthEvaluator`. It installs no diagnostic
rule pack, threshold, polling request, vehicle command, UI control, or network
dependency. All tests use synthetic rules and normalized observations.

## Contract audit

- `Observation` already enforces a typed available value, canonical unit metadata,
  explicit hardware/simulator/replay source, session generation, monotonic receipt,
  and separate opaque source timestamp. Receipt age is not ECU sample age.
- `HealthRule` and `HealthRulePack` already have versions, provenance, explicit
  applicability, and read-only input requirements. Their evaluation callbacks
  remain the only rule boundary; H03A does not add another evaluator.
- `HealthAssessment` already separates per-rule coverage from findings, while
  findings separately carry severity and evidence confidence. An evaluated rule
  with no findings says only that this rule evaluated its declared inputs. It
  makes no whole-vehicle health claim.
- The H01 evaluator rejected mixed signal definitions and non-available data,
  but any old stale or unavailable receipt could poison a newer complete run.
  It could not check a single receipt against a rule's expected PID, module,
  unit, or type, or check timing across different required signals. Its result
  did not carry pack/rule provenance after evaluation.

## Evaluation policy

Each requirement may declare expected Mode 01 PID, verified module identity,
canonical unit, and value type. If specified, the observed identity must match.
Different identities cannot be combined into one input window. A null module
means the module is unknown: a module-specific rule must require a verified
module and will get `IDENTITY_MISMATCH` when the source cannot provide it. Rules
must document their expected identities and applicability before activation.

The evaluator examines only the requested signal ID and fails closed if that
list contains a foreign source or session generation. Future-dated receipts
are stale. Within the matching source and generation, a non-available receipt
ends the preceding sampling run. A later available receipt starts a new run;
older unavailable and expired available receipts cannot poison a sufficiently
fresh new run. A latest unavailable, not-sampled, stale, disconnected, or error
receipt blocks evaluation. Duplicate receipt times in an active window are
invalid. Only fresh available receipts from the latest run can satisfy sample
count and minimum receipt span. Missing and insufficient input reasons remain
explicit in `AssessmentCoverage`.

For multi-input rules, at least one receipt from every required signal must fit
within the nonnegative `maximumCrossSignalGapMillis` span (zero by default).
Overlapping first-to-last windows alone do not qualify sparse signals. The rule
must still select relevant receipts from the supplied windows; this bound does
not claim that the ECU sampled signals simultaneously.
Requirements are passed to rules in signal-ID order, each evidence list in
receipt order. Returned finding evidence is also normalized to signal-ID and
receipt order; next-check order remains rule-authored priority order. Coverage
reasons are enum ordered for stable serialization.

`EvaluationContext.operatingContext` carries only caller-supplied facts for
pack/rule applicability. An absent fact is not inferred or approved by the
evaluator. Pack and rule callbacks must explicitly return `APPROVED`; otherwise
coverage is `NOT_APPLICABLE` with an explicit reason. Assessments retain the
pack provenance and source; each coverage record retains rule provenance.
Findings retain the normalized observation and its receipt age. Provenance
references must identify the reviewed source for each future rule-pack version.

## Dependencies for H03B/H03C

H03B/H03C must supply reviewed rule applicability, operating facts, verified
signal identity, provenance, sample and timing bounds, thresholds, limitations,
and user wording before a rule is activated. H02A/H02B admission and receipt
cadence do not guarantee that the evaluator has sufficient evidence. The
current unaddressed live ELM responses provide no verified ECU module or ECU
sample timestamp, so module-specific rules cannot claim that identity. No
physical adapter or vehicle was used for H03A; hardware qualification remains
separate.
