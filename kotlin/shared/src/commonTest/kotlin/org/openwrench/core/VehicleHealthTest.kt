package org.openwrench.core

import kotlin.test.*

class VehicleHealthTest {
    private val signal = SignalDefinition("rpm", "Engine speed", "rpm", SignalType.NUMBER, SignalSupport.SUPPORTED, "010C")
    private val identity = RuleIdentity("synthetic-contract", "1")
    private val provenance = EvidenceProvenance("synthetic-test", "Contract test only; no diagnostic threshold")

    private fun observation(value: Double? = 0.0, availability: SignalAvailability = SignalAvailability.AVAILABLE,
        source: ObservationSource = ObservationSource.HARDWARE, generation: Long = 2, receipt: Long = 100,
        definition: SignalDefinition = signal, reasons: Set<ObservationQualityReason> = emptySet()) =
        Observation(definition, value?.let { SignalValue.Number(it) }, availability, source, generation, receipt, qualityReasons = reasons)

    private class TestRule(private val required: InputRequirement = InputRequirement("rpm", 1, 0, 1_000),
        private val approved: RuleApplicability = RuleApplicability.APPROVED,
        private val emitFinding: Boolean = true) : HealthRule {
        override val identity = RuleIdentity("synthetic-contract", "1")
        override val requirements = listOf(required)
        override val provenance = EvidenceProvenance("synthetic-test", "Contract test only")
        var calls = 0
        override fun applicability(context: EvaluationContext) = approved
        override fun evaluate(evidence: Map<String, List<HealthEvidence>>): List<HealthFinding> {
            calls++
            if (!emitFinding) return emptyList()
            return listOf(HealthFinding(identity, HealthSeverity.INFORMATIONAL, EvidenceConfidence.LOW,
                "Synthetic evidence was supplied", listOf("No vehicle interpretation"), listOf("Review source"), evidence.getValue("rpm")))
        }
    }

    private fun pack(rule: TestRule, approved: RuleApplicability = RuleApplicability.APPROVED) = object : HealthRulePack {
        override val id = "synthetic"
        override val version = "1"
        override val provenance = this@VehicleHealthTest.provenance
        override val rules = listOf(rule)
        override fun applicability(context: EvaluationContext) = approved
    }

    private fun run(rule: TestRule, observations: List<Observation>, connected: Boolean = true,
        source: ObservationSource = ObservationSource.HARDWARE, approval: RuleApplicability = RuleApplicability.APPROVED) =
        HealthEvaluator.evaluate(pack(rule, approval), EvaluationContext(2, 200, source, connected), observations)

    @Test fun missingIsDistinctFromZeroAndCoverageIsNotHealth() {
        assertFailsWith<IllegalArgumentException> { observation(null) }
        val rule = TestRule()
        val missing = run(rule, emptyList())
        assertEquals(CoverageStatus.INSUFFICIENT_DATA, missing.coverage.single().status)
        assertEquals(setOf(InsufficientDataReason.MISSING), missing.coverage.single().reasons)
        assertTrue(missing.findings.isEmpty())
        val zero = run(rule, listOf(observation(0.0)))
        assertEquals(CoverageStatus.EVALUATED, zero.coverage.single().status)
        assertEquals(0.0, (zero.findings.single().evidence.single().observation.value as SignalValue.Number).value)
        assertEquals(100, zero.findings.single().evidence.single().ageMillis)
        assertEquals(EvidenceConfidence.LOW, zero.findings.single().confidence)
        assertEquals(HealthSeverity.INFORMATIONAL, zero.findings.single().severity)
        assertEquals(1, rule.calls)
        val noFinding = run(TestRule(emitFinding = false), listOf(observation(0.0)))
        assertEquals(CoverageStatus.EVALUATED, noFinding.coverage.single().status)
        assertTrue(noFinding.findings.isEmpty())
    }

    @Test fun unsupportedInvalidAndStaleNeverReachRule() {
        val cases = listOf(
            observation(null, SignalAvailability.NOT_SAMPLED, definition = signal.copy(support = SignalSupport.UNSUPPORTED)) to InsufficientDataReason.UNSUPPORTED_SIGNAL,
            observation(null, SignalAvailability.ERROR, reasons = setOf(ObservationQualityReason.MALFORMED)) to InsufficientDataReason.INVALID,
            observation(null, SignalAvailability.STALE, reasons = setOf(ObservationQualityReason.EXPIRED)) to InsufficientDataReason.STALE,
            observation(1.0, receipt = 0) to InsufficientDataReason.STALE,
        )
        for ((sample, expected) in cases) {
            val rule = TestRule(InputRequirement("rpm", 1, 0, 50))
            val result = run(rule, listOf(sample))
            assertTrue(expected in result.coverage.single().reasons)
            assertTrue(result.findings.isEmpty())
            assertEquals(0, rule.calls)
        }
    }

    @Test fun laterMissingReceiptCannotReuseAnOlderAvailableValue() {
        val earlier = observation(700.0, receipt = 100)
        val cases = listOf(
            SignalAvailability.UNAVAILABLE to InsufficientDataReason.UNAVAILABLE,
            SignalAvailability.NOT_SAMPLED to InsufficientDataReason.NOT_SAMPLED,
            SignalAvailability.DISCONNECTED to InsufficientDataReason.DISCONNECTED,
        )
        for ((availability, reason) in cases) {
            val rule = TestRule()
            val later = observation(null, availability, receipt = 150)
            for (samples in listOf(listOf(earlier, later), listOf(later, earlier))) {
                val result = run(rule, samples)
                assertEquals(CoverageStatus.INSUFFICIENT_DATA, result.coverage.single().status)
                assertTrue(reason in result.coverage.single().reasons)
                assertTrue(result.findings.isEmpty())
            }
            assertEquals(0, rule.calls)
        }
    }

    @Test fun disconnectedAndCrossSessionEvidenceFailClosed() {
        val disconnectedRule = TestRule()
        val disconnected = run(disconnectedRule, listOf(observation()), connected = false)
        assertEquals(setOf(InsufficientDataReason.DISCONNECTED), disconnected.coverage.single().reasons)
        assertEquals(0, disconnectedRule.calls)
        val otherRule = TestRule()
        val other = run(otherRule, listOf(observation(generation = 1)))
        assertTrue(InsufficientDataReason.WRONG_SESSION in other.coverage.single().reasons)
        assertEquals(0, otherRule.calls)
        val mixed = listOf(observation(receipt = 150), observation(generation = 1, receipt = 100))
        assertEquals(run(TestRule(), mixed), run(TestRule(), mixed.reversed()))
        assertTrue(InsufficientDataReason.WRONG_SESSION in run(TestRule(), mixed).coverage.single().reasons)
    }

    @Test fun provenanceIsSeparateAcrossHardwareSimulatorAndReplay() {
        for (source in ObservationSource.entries) {
            val rule = TestRule()
            val result = run(rule, listOf(observation(source = source)), source = source)
            assertEquals(source, result.findings.single().evidence.single().observation.source)
            assertEquals(source, result.source)
            assertEquals(1, rule.calls)
        }
        val rule = TestRule()
        val mixed = run(rule, listOf(observation(source = ObservationSource.SIMULATOR)))
        assertTrue(InsufficientDataReason.SOURCE_MISMATCH in mixed.coverage.single().reasons)
        assertEquals(0, rule.calls)
        val both = listOf(observation(receipt = 150), observation(source = ObservationSource.SIMULATOR))
        assertEquals(run(TestRule(), both), run(TestRule(), both.reversed()))
        assertTrue(InsufficientDataReason.SOURCE_MISMATCH in run(TestRule(), both).coverage.single().reasons)
    }

    @Test fun applicabilitySamplesAndWindowAreDeterministicGates() {
        val rule = TestRule(InputRequirement("rpm", 2, 50, 1_000))
        val samples = listOf(observation(2.0, receipt = 150), observation(1.0, receipt = 100))
        val first = run(rule, samples)
        val second = run(rule, samples.reversed())
        assertEquals(first, second)
        assertEquals(listOf(100L, 150L), first.findings.single().evidence.map { it.observation.receivedAtMonotonicMillis })
        assertEquals(CoverageStatus.EVALUATED, first.coverage.single().status)
        assertTrue(first.coverage.single().reasons.isEmpty())
        assertTrue(run(TestRule(InputRequirement("rpm", 2, 60, 1_000)), samples).findings.isEmpty())
        val unapproved = TestRule()
        assertEquals(CoverageStatus.NOT_APPLICABLE, run(unapproved, samples, approval = RuleApplicability.UNAPPROVED).coverage.single().status)
        assertEquals(0, unapproved.calls)
        val unsupported = TestRule(approved = RuleApplicability.UNSUPPORTED)
        assertEquals(CoverageStatus.NOT_APPLICABLE, run(unsupported, samples).coverage.single().status)
        assertEquals(0, unsupported.calls)
    }

    @Test fun conflictingReceiptsUnitsPidAndModulesDoNotDependOnInputOrder() {
        val duplicateTime = listOf(observation(1.0), observation(2.0))
        val wrongUnit = listOf(observation(1.0), observation(2.0, receipt = 150, definition = signal.copy(unit = "rad/s")))
        val wrongPid = listOf(observation(1.0), observation(2.0, receipt = 150, definition = signal.copy(pid = "010D")))
        val wrongModule = listOf(observation(1.0, definition = signal.copy(module = "A")),
            observation(2.0, receipt = 150, definition = signal.copy(module = "B")))
        for (samples in listOf(duplicateTime, wrongUnit, wrongPid, wrongModule)) {
            val rule = TestRule(InputRequirement("rpm", 2, 50, 1_000))
            assertTrue(InsufficientDataReason.INVALID in run(rule, samples).coverage.single().reasons)
            assertTrue(InsufficientDataReason.INVALID in run(rule, samples.reversed()).coverage.single().reasons)
            assertEquals(0, rule.calls)
        }
    }

    @Test fun normalizedObservationRejectsWrongTypeAndNoncanonicalAvailability() {
        assertFailsWith<IllegalArgumentException> { Observation(signal, SignalValue.Integer(0), SignalAvailability.AVAILABLE, ObservationSource.HARDWARE, 2, 100) }
        assertFailsWith<IllegalArgumentException> { observation(0.0, SignalAvailability.UNAVAILABLE) }
        assertFailsWith<IllegalArgumentException> { observation(0.0, reasons = setOf(ObservationQualityReason.MALFORMED)) }
        assertFailsWith<IllegalArgumentException> { observation(receipt = -1) }
    }

    @Test fun freshWindowIgnoresOldAvailableAndSupersededUnavailableReceipts() {
        val rule = TestRule(InputRequirement("rpm", 2, 20, 50))
        val samples = listOf(
            observation(1.0, receipt = 10),
            observation(null, SignalAvailability.UNAVAILABLE, receipt = 80),
            observation(2.0, receipt = 160), observation(3.0, receipt = 190),
        )
        val first = run(rule, samples)
        assertEquals(first, run(rule, samples.reversed()))
        assertEquals(CoverageStatus.EVALUATED, first.coverage.single().status)
        assertEquals(listOf(160L, 190L), first.findings.single().evidence.map { it.observation.receivedAtMonotonicMillis })
        assertEquals(provenance, first.packProvenance)
        assertEquals(rule.provenance, first.coverage.single().provenance)
    }

    @Test fun recentUnavailableAndShortOrStaleWindowsRemainInsufficient() {
        val requirement = InputRequirement("rpm", 2, 30, 50)
        val unavailable = run(TestRule(requirement), listOf(observation(1.0, receipt = 160),
            observation(null, SignalAvailability.UNAVAILABLE, receipt = 190))).coverage.single()
        assertTrue(InsufficientDataReason.UNAVAILABLE in unavailable.reasons)
        assertEquals(CoverageStatus.INSUFFICIENT_DATA, unavailable.status)
        val short = run(TestRule(requirement), listOf(observation(1.0, receipt = 170), observation(2.0, receipt = 190))).coverage.single()
        assertTrue(InsufficientDataReason.WINDOW_TOO_SHORT in short.reasons)
        val tooFew = run(TestRule(requirement), listOf(observation(1.0, receipt = 100), observation(2.0, receipt = 190))).coverage.single()
        assertTrue(InsufficientDataReason.TOO_FEW_SAMPLES in tooFew.reasons)
        assertTrue(InsufficientDataReason.STALE in tooFew.reasons)
    }

    @Test fun singleInputMustMatchDeclaredSignalIdentity() {
        val expected = InputRequirement("rpm", 1, 0, 1_000, expectedPid = "010C",
            expectedModule = "verified-ecu", expectedUnit = "rpm", expectedType = SignalType.NUMBER)
        val rule = TestRule(expected)
        assertEquals(CoverageStatus.EVALUATED,
            run(rule, listOf(observation(definition = signal.copy(module = "verified-ecu")))).coverage.single().status)
        for (wrong in listOf(signal, signal.copy(pid = "010D", module = "verified-ecu"),
            signal.copy(unit = "rad/s", module = "verified-ecu"))) {
            val result = run(rule, listOf(observation(definition = wrong)))
            assertTrue(InsufficientDataReason.IDENTITY_MISMATCH in result.coverage.single().reasons)
        }
    }

    @Test fun crossSignalWindowsAndEvidenceOrderAreDeterministic() {
        val second = SignalDefinition("speed", "Speed", "km/h", SignalType.NUMBER, SignalSupport.SUPPORTED, "010D")
        val rule = object : HealthRule {
            override val identity = RuleIdentity("two-signals", "1")
            override val requirements = listOf(InputRequirement("speed", 2, 20, 1_000), InputRequirement("rpm", 2, 20, 1_000))
            override val provenance = this@VehicleHealthTest.provenance
            override val maximumCrossSignalGapMillis = 10L
            var calls = 0
            override fun applicability(context: EvaluationContext) = RuleApplicability.APPROVED
            override fun evaluate(evidence: Map<String, List<HealthEvidence>>): List<HealthFinding> {
                calls++
                return listOf(HealthFinding(identity, HealthSeverity.INFORMATIONAL, EvidenceConfidence.LOW,
                    "Synthetic pair", emptyList(), emptyList(), evidence.values.flatten().reversed()))
            }
        }
        val pack = object : HealthRulePack {
            override val id = "synthetic-pair"
            override val version = "2"
            override val provenance = this@VehicleHealthTest.provenance
            override val rules = listOf(rule)
            override fun applicability(context: EvaluationContext) = RuleApplicability.APPROVED
        }
        val context = EvaluationContext(2, 300, ObservationSource.HARDWARE, true)
        val rpm = listOf(observation(1.0, receipt = 100), observation(2.0, receipt = 130))
        val far = listOf(observation(3.0, receipt = 200, definition = second), observation(4.0, receipt = 230, definition = second))
        val insufficient = HealthEvaluator.evaluate(pack, context, rpm + far)
        assertEquals(setOf(InsufficientDataReason.INCOMPATIBLE_WINDOWS), insufficient.coverage.single().reasons)
        assertEquals(0, rule.calls)
        val sparseOverlap = listOf(observation(3.0, receipt = 140, definition = second),
            observation(4.0, receipt = 160, definition = second))
        val sparseRpm = listOf(observation(1.0, receipt = 100), observation(2.0, receipt = 200))
        assertEquals(setOf(InsufficientDataReason.INCOMPATIBLE_WINDOWS),
            HealthEvaluator.evaluate(pack, context, sparseRpm + sparseOverlap).coverage.single().reasons)
        val near = listOf(observation(3.0, receipt = 140, definition = second), observation(4.0, receipt = 170, definition = second))
        val first = HealthEvaluator.evaluate(pack, context, rpm + near)
        assertEquals(first, HealthEvaluator.evaluate(pack, context, (rpm + near).reversed()))
        assertEquals(listOf("rpm", "rpm", "speed", "speed"), first.findings.single().evidence.map { it.observation.definition.id })
        assertEquals(CoverageStatus.EVALUATED, first.coverage.single().status)
    }

    @Test fun operatingContextAndApprovalAreExplicit() {
        val rule = object : HealthRule {
            override val identity = RuleIdentity("context-only", "1")
            override val requirements = listOf(InputRequirement("rpm", 1, 0, 1_000))
            override val provenance = this@VehicleHealthTest.provenance
            override fun applicability(context: EvaluationContext) = if (context.operatingContext["synthetic-state"] == "approved")
                RuleApplicability.APPROVED else RuleApplicability.UNAPPROVED
            override fun evaluate(evidence: Map<String, List<HealthEvidence>>) = emptyList<HealthFinding>()
        }
        val pack = object : HealthRulePack {
            override val id = "synthetic-context"
            override val version = "1"
            override val provenance = this@VehicleHealthTest.provenance
            override val rules = listOf(rule)
            override fun applicability(context: EvaluationContext) = RuleApplicability.APPROVED
        }
        val base = EvaluationContext(2, 200, ObservationSource.HARDWARE, true)
        val unknown = HealthEvaluator.evaluate(pack, base, listOf(observation()))
        assertEquals(CoverageStatus.NOT_APPLICABLE, unknown.coverage.single().status)
        assertEquals(setOf(InsufficientDataReason.UNAPPROVED), unknown.coverage.single().reasons)
        val approved = HealthEvaluator.evaluate(pack, base.copy(operatingContext = mapOf("synthetic-state" to "approved")), listOf(observation()))
        assertEquals(CoverageStatus.EVALUATED, approved.coverage.single().status)
        assertTrue(approved.findings.isEmpty())
    }
}
