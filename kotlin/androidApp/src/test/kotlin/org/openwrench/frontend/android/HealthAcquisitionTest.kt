package org.openwrench.frontend.android

import org.openwrench.core.*
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HealthAcquisitionTest {
    private fun need(id: String) = InputRequirement(id, 2, 500, 5_000)

    @Test fun deduplicatesAndKeepsDashboardRateAndPriority() {
        val dashboard = mapOf(0x0C to WatchRate.HIGH)
        val plan = HealthInputPlanner.plan(dashboard,
            listOf(need("rpm"), need("coolant"), need("coolant"), need("speed")),
            setOf(0x0C, 0x05, 0x0D))
        assertEquals(dashboard, plan.dashboard)
        assertEquals(WatchRate.HIGH, plan.acquisition[0x0C])
        assertEquals(setOf(0x0C), plan.acquisition.keys)
        assertEquals(listOf(HealthAdmission.SHARED_WITH_DASHBOARD, HealthAdmission.CADENCE_CONFLICT,
            HealthAdmission.CADENCE_CONFLICT, HealthAdmission.CADENCE_CONFLICT), plan.decisions.map { it.reason })
        val healthOnly = HealthInputPlanner.plan(emptyMap(), listOf(need("coolant"), need("coolant")), setOf(0x05))
        assertEquals(setOf(0x05), healthOnly.acquisition.keys)
        assertEquals(listOf(HealthAdmission.ADMITTED, HealthAdmission.SHARED_WITH_HEALTH),
            healthOnly.decisions.map { it.reason })
    }

    @Test fun worstCaseReadBoundBlocksAdditionalHealthReadsEvenAfterFastSamples() {
        val dashboard = mapOf(0x0C to WatchRate.HIGH)
        val requirements = listOf(need("coolant"))
        val advertised = setOf(0x0C, 0x05)
        val plan = HealthInputPlanner.plan(dashboard, requirements, advertised)
        assertEquals(HealthAdmission.CADENCE_CONFLICT, plan.decisions.single().reason)
        assertEquals(dashboard, plan.acquisition)
        assertTrue(2L * HealthInputPlanner.MAX_READ_MILLIS + HealthInputPlanner.INTER_READ_MILLIS >= 5_000L)
    }

    @Test fun structuredFuelRequirementHasSeparateAdmissionAndCapabilityState() {
        val fuel = need("fuel-system-status")
        val disconnected = HealthInputPlanner.plan(emptyMap(), listOf(fuel), emptySet())
        assertEquals(HealthInputKind.STRUCTURED, disconnected.decisions.single().kind)
        assertEquals(HealthAdmission.NOT_ADVERTISED, disconnected.decisions.single().reason)
        val admitted = HealthInputPlanner.plan(emptyMap(), listOf(fuel, fuel), setOf(0x03))
        assertTrue(admitted.acquisition.isEmpty())
        assertEquals(mapOf(0x03 to WatchRate.NORMAL), admitted.structured)
        assertEquals(listOf(HealthAdmission.ADMITTED, HealthAdmission.SHARED_WITH_HEALTH),
            admitted.decisions.map { it.reason })
        val blocked = HealthInputPlanner.plan(mapOf(0x0C to WatchRate.HIGH), listOf(fuel), setOf(0x03, 0x0C))
        assertEquals(HealthAdmission.CADENCE_CONFLICT, blocked.decisions.single().reason)
        assertTrue(blocked.structured.isEmpty())
    }

    @Test fun mixedHealthOnlyInputsDeferTheLaterTypeInEitherOrder() {
        val fuel = need("fuel-system-status")
        val coolant = need("coolant")
        val advertised = setOf(0x03, 0x05)
        val scalarFirst = HealthInputPlanner.plan(emptyMap(), listOf(coolant, fuel), advertised)
        assertEquals(listOf(HealthAdmission.ADMITTED, HealthAdmission.CADENCE_CONFLICT),
            scalarFirst.decisions.map { it.reason })
        assertEquals(setOf(0x05), scalarFirst.acquisition.keys)
        assertTrue(scalarFirst.structured.isEmpty())
        val structuredFirst = HealthInputPlanner.plan(emptyMap(), listOf(fuel, coolant), advertised)
        assertEquals(listOf(HealthAdmission.ADMITTED, HealthAdmission.CADENCE_CONFLICT),
            structuredFirst.decisions.map { it.reason })
        assertEquals(setOf(0x03), structuredFirst.structured.keys)
        assertTrue(structuredFirst.acquisition.isEmpty())
        assertEquals(HealthInputKind.STRUCTURED, scalarFirst.decisions.last().kind)
        assertEquals(HealthInputKind.SCALAR, structuredFirst.decisions.last().kind)
    }

    @Test fun fullCatalogNeverEvictsDashboardAndRejectsUnknownOrUnsupportedInputs() {
        val dashboard = ObdDecoder.scalarPids.sorted().associateWith { WatchRate.NORMAL }
        val plan = HealthInputPlanner.plan(dashboard, listOf(need("not-in-catalog"), need("rpm")),
            ObdDecoder.scalarPids)
        assertEquals(dashboard, plan.acquisition)
        assertEquals(HealthAdmission.UNKNOWN_SIGNAL, plan.decisions[0].reason)
        assertEquals(HealthAdmission.SHARED_WITH_DASHBOARD, plan.decisions[1].reason)
        assertFailsWith<IllegalArgumentException> {
            HealthInputPlanner.plan(dashboard + (0xFF to WatchRate.LOW), emptyList(), ObdDecoder.scalarPids + 0xFF)
        }
        assertFailsWith<IllegalArgumentException> {
            HealthInputPlanner.plan(mapOf(0x03 to WatchRate.LOW), emptyList(), setOf(0x03))
        }
        val unsupported = HealthInputPlanner.plan(emptyMap(), listOf(need("rpm")), emptySet())
        assertEquals(HealthAdmission.NOT_ADVERTISED, unsupported.decisions.single().reason)
        assertFailsWith<IllegalArgumentException> {
            HealthInputPlanner.plan(mapOf(0x0C to WatchRate.NORMAL), emptyList(), emptySet())
        }
    }

    @Test fun bridgeKeepsCanonicalIdentityTypeAndReceiptProvenance() {
        val reading = NativeReading(0x0C, DecodedReading.Scalar(1726.5))
        val observation = HealthObservationBridge.normalize(reading, SignalSupport.SUPPORTED,
            ObservationSource.HARDWARE, 8, 1_000, module = "7E8")
        assertEquals("010C", observation.definition.pid)
        assertEquals("7E8", observation.definition.module)
        assertEquals(ObdDecoder.catalog.getValue(0x0C).unit, observation.definition.unit)
        assertEquals(SignalValue.Number(1726.5), observation.value)
        assertEquals(8, observation.sessionGeneration)
        assertEquals(1_000, observation.receivedAtMonotonicMillis)
        assertEquals(250, observation.ageMillis(1_250))
        assertEquals(null, observation.sourceTimestamp)
    }

    @Test fun unavailableReceiptSupersedesValueAndMixedModulesCannotFormWindow() {
        val first = HealthObservationBridge.normalize(NativeReading(0x0C, DecodedReading.Scalar(1000.0)),
            SignalSupport.SUPPORTED, ObservationSource.HARDWARE, 4, 1_000, module = "7E8")
        val unavailable = HealthObservationBridge.normalize(NativeReading(0x0C, null),
            SignalSupport.SUPPORTED, ObservationSource.HARDWARE, 4, 1_500, module = "7E8")
        assertEquals(null, unavailable.value)
        assertEquals(SignalAvailability.UNAVAILABLE, unavailable.availability)
        assertTrue(ObservationQualityReason.NO_DATA in unavailable.qualityReasons)
        val otherModule = first.copy(definition = first.definition.copy(module = "7E9"), receivedAtMonotonicMillis = 1_500)
        val rule = object : HealthRule {
            override val identity = RuleIdentity("synthetic", "1")
            override val requirements = listOf(need("rpm"))
            override val provenance = EvidenceProvenance("synthetic", "Gate test")
            override fun applicability(context: EvaluationContext) = RuleApplicability.APPROVED
            override fun evaluate(evidence: Map<String, List<HealthEvidence>>): List<HealthFinding> = emptyList()
        }
        val pack = object : HealthRulePack {
            override val id = "synthetic"
            override val version = "1"
            override val provenance = EvidenceProvenance("synthetic", "Gate test")
            override val rules = listOf(rule)
            override fun applicability(context: EvaluationContext) = RuleApplicability.APPROVED
        }
        val context = EvaluationContext(4, 1_500, ObservationSource.HARDWARE, true)
        val unavailableCoverage = HealthEvaluator.evaluate(pack, context, listOf(first, unavailable)).coverage.single()
        assertTrue(InsufficientDataReason.UNAVAILABLE in unavailableCoverage.reasons)
        val mixedCoverage = HealthEvaluator.evaluate(pack, context, listOf(first, otherModule)).coverage.single()
        assertTrue(InsufficientDataReason.INVALID in mixedCoverage.reasons)
        val wrongGeneration = HealthEvaluator.evaluate(pack, context, listOf(first, first.copy(sessionGeneration = 5,
            receivedAtMonotonicMillis = 1_500))).coverage.single()
        assertTrue(InsufficientDataReason.WRONG_SESSION in wrongGeneration.reasons)
    }

    @Test fun replayAndSimulatorRemainDistinctAndStaleHasNoValue() {
        val reading = NativeReading(0x0C, DecodedReading.Scalar(0.0))
        val simulator = HealthObservationBridge.normalize(reading, SignalSupport.SUPPORTED,
            ObservationSource.SIMULATOR, 1, 10)
        val replay = HealthObservationBridge.normalize(reading, SignalSupport.SUPPORTED,
            ObservationSource.REPLAY, 2, 20)
        val stale = HealthObservationBridge.normalize(reading, SignalSupport.SUPPORTED,
            ObservationSource.HARDWARE, 3, 30, SignalAvailability.STALE,
            qualityReasons = setOf(ObservationQualityReason.EXPIRED))
        assertEquals(SignalValue.Number(0.0), simulator.value)
        assertEquals(ObservationSource.REPLAY, replay.source)
        assertFalse(simulator.source == replay.source)
        assertEquals(null, stale.value)
        assertEquals(SignalAvailability.STALE, stale.availability)
    }
}
