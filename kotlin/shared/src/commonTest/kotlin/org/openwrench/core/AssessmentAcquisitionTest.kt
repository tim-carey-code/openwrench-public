package org.openwrench.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** All profiles, values and timing limits in this file are synthetic. No vehicle is accessed. */
class AssessmentAcquisitionTest {
    private class Clock(var time: Long = 100) : AssessmentClock { override fun nowMillis() = time }
    private fun input(
        id: String, pid: String, unit: String?, type: SignalType = SignalType.NUMBER,
        samples: Int = 1, window: Long = 0, age: Long = 1_000,
        module: String? = null, contextKeys: Set<String> = emptySet(), invalidatedBy: Set<String> = emptySet(),
    ) = AssessmentInput(InputRequirement(id, samples, window, age, pid, module, unit, type), contextKeys, invalidatedBy)

    private val rpm = input("rpm", "010C", "rpm")
    private val coolant = input("coolant", "0105", "°C")
    private val fuel = input("fuel-system-status", "0103", null, SignalType.STRUCTURED)
    private fun profile(
        required: List<AssessmentInput> = listOf(rpm, coolant), optional: List<AssessmentInput> = emptyList(),
        budget: Long = 1_000, cap: Int = 12, gap: Long = 20,
    ) = AssessmentProfile(AssessmentProfileIdentity("synthetic-acquisition", "1"),
        AssessmentMode.SYNTHETIC_MIXED_READ_ONLY, required, optional, budget, cap, gap)

    private fun observation(input: AssessmentInput, time: Long, source: ObservationSource = ObservationSource.HARDWARE,
        generation: Long = 7, module: String? = null, noData: Boolean = false): Observation {
        val requirement = input.requirement
        val definition = SignalDefinition(requirement.signalId, "Synthetic ${requirement.signalId}",
            requirement.expectedUnit, requirement.expectedType!!, SignalSupport.SUPPORTED,
            requirement.expectedPid, module)
        return Observation(definition, if (noData) null else if (requirement.expectedType == SignalType.STRUCTURED)
            SignalValue.Structured(mapOf("system_1" to "closed loop", "system_2" to "engine off"))
            else SignalValue.Number(1.0), if (noData) SignalAvailability.UNAVAILABLE else SignalAvailability.AVAILABLE,
            source, generation, time, qualityReasons = if (noData) setOf(ObservationQualityReason.NO_DATA) else emptySet())
    }

    private fun collector(profile: AssessmentProfile, clock: Clock, advertised: Set<Int> = setOf(0x03, 0x05, 0x0C)) =
        AssessmentCollector(profile, "assessment-1", 7, ObservationSource.HARDWARE, advertised,
            mapOf("operation" to "idle"), clock)

    private fun completeNext(collector: AssessmentCollector, clock: Clock, inputs: List<AssessmentInput>,
        time: Long, noData: Boolean = false): AssessmentReadAction {
        val action = assertNotNull(collector.nextAction())
        clock.time = time
        val input = inputs.single { it.requirement.signalId == action.signalId }
        assertTrue(collector.complete(action, observation(input, time, noData = noData)))
        return action
    }

    @Test fun requiredEvidenceDeterminesReadinessWhileMissingOptionalIsVisible() {
        val clock = Clock()
        val current = profile(optional = listOf(fuel))
        val collector = collector(current, clock)
        val inputs = current.inputs
        completeNext(collector, clock, inputs, 101)
        assertEquals(AcquisitionState.COLLECTING, collector.snapshot().state)
        completeNext(collector, clock, inputs, 102)
        val ready = collector.snapshot()
        assertEquals(AcquisitionState.EVIDENCE_READY, ready.state)
        assertEquals(2, ready.attemptsDispatched)
        assertEquals(setOf("rpm", "coolant"), ready.selectedEvidence.keys)
        assertFalse(ready.inputs.single { it.signalId == "fuel-system-status" }.qualified)
        assertTrue(InputQualificationReason.MISSING in ready.inputs.last().reasons)
        assertNull(collector.nextAction())
    }

    @Test fun existingOptionalEvidenceCanJoinCohortWithoutConsumingACommand() {
        val clock = Clock()
        val current = profile(listOf(rpm), optional = listOf(coolant), cap = 1)
        val collector = collector(current, clock)
        clock.time = 101
        assertFalse(collector.offerExistingOptional("replaced-instance", observation(coolant, 101)))
        assertFalse(collector.offerExistingOptional("assessment-1",
            observation(coolant, 101, source = ObservationSource.REPLAY)))
        assertTrue(collector.offerExistingOptional("assessment-1", observation(coolant, 101)))
        assertEquals(0, collector.snapshot().attemptsDispatched)
        completeNext(collector, clock, current.inputs, 102)
        val ready = collector.snapshot()
        assertEquals(AcquisitionState.EVIDENCE_READY, ready.state)
        assertEquals(setOf("rpm", "coolant"), ready.selectedEvidence.keys)
        assertEquals(1, ready.attemptsDispatched)
    }

    @Test fun differingRequiredSampleCountsCompleteWithinTheExactCohortCommandCap() {
        val repeatedCoolant = coolant.copy(requirement = coolant.requirement.copy(minimumSamples = 2))
        for (required in listOf(listOf(rpm, repeatedCoolant), listOf(repeatedCoolant, rpm))) {
            val clock = Clock()
            val current = profile(required, cap = 4)
            val collector = collector(current, clock)
            val actions = (101L..104L).map { time -> completeNext(collector, clock, current.inputs, time) }

            val ready = collector.snapshot()
            assertEquals(AcquisitionState.EVIDENCE_READY, ready.state)
            assertEquals(4, ready.attemptsDispatched)
            assertNull(ready.limitReason)
            assertEquals(mapOf("rpm" to 2, "coolant" to 2),
                actions.groupingBy { it.signalId }.eachCount())
            assertEquals(mapOf("rpm" to 2, "coolant" to 2),
                ready.selectedEvidence.mapValues { it.value.size })
            assertTrue(ready.inputs.all { it.qualified })
            assertNull(collector.nextAction())
        }
    }

    @Test fun optionalSampleMinimumDoesNotIncreaseRequiredDispatchCount() {
        val repeatedCoolant = coolant.copy(requirement = coolant.requirement.copy(minimumSamples = 2))
        val clock = Clock()
        val current = profile(listOf(rpm), optional = listOf(repeatedCoolant), cap = 1)
        val collector = collector(current, clock)
        for (time in 101L..102L) {
            clock.time = time
            assertTrue(collector.offerExistingOptional("assessment-1", observation(repeatedCoolant, time)))
        }
        assertEquals("rpm", completeNext(collector, clock, current.inputs, 103).signalId)

        val ready = collector.snapshot()
        assertEquals(AcquisitionState.EVIDENCE_READY, ready.state)
        assertEquals(1, ready.attemptsDispatched)
        assertEquals(setOf("rpm"), ready.selectedEvidence.keys)
        assertEquals(1, ready.selectedEvidence.getValue("rpm").size)
        assertNull(collector.nextAction())
    }

    @Test fun optionalEvidenceSelectionIsIndependentOfProfileOrder() {
        val load = input("load", "0104", "%")
        fun selectedEvidence(optional: List<AssessmentInput>): Map<String, List<Observation>> {
            val clock = Clock()
            val current = profile(listOf(rpm), optional = optional, cap = 1, gap = 1)
            val collector = collector(current, clock, advertised = setOf(0x04, 0x05, 0x0C))
            clock.time = 101
            assertTrue(collector.offerExistingOptional("assessment-1", observation(coolant, 101)))
            clock.time = 103
            assertTrue(collector.offerExistingOptional("assessment-1", observation(load, 103)))
            val action = assertNotNull(collector.nextAction())
            assertEquals("rpm", action.signalId)
            assertTrue(collector.complete(action, observation(rpm, 102)))
            assertEquals(AcquisitionState.EVIDENCE_READY, collector.snapshot().state)
            return collector.snapshot().selectedEvidence
        }

        val forward = selectedEvidence(listOf(coolant, load))
        val reversed = selectedEvidence(listOf(load, coolant))
        assertEquals(forward, reversed)
        assertEquals(setOf("rpm", "coolant"), forward.keys)
    }

    @Test fun closeClusterCannotQualifyUnrelatedSecondSamplesAndOrderingIsStable() {
        val twoRpm = rpm.copy(requirement = rpm.requirement.copy(minimumSamples = 2, minimumWindowMillis = 20))
        val twoCoolant = coolant.copy(requirement = coolant.requirement.copy(minimumSamples = 2, minimumWindowMillis = 20))
        val requirements = listOf(twoRpm.requirement, twoCoolant.requirement)
        val evidence = mapOf(
            "rpm" to listOf(observation(twoRpm, 100), observation(twoRpm, 200)),
            "coolant" to listOf(observation(twoCoolant, 102), observation(twoCoolant, 300)),
        )
        assertTrue(selectCohort(requirements, evidence, 310, 5).isEmpty())
        val completed = evidence + ("coolant" to evidence.getValue("coolant") + observation(twoCoolant, 202))
        val forward = selectCohort(requirements, completed, 310, 5)
        val reversed = selectCohort(requirements.reversed(), completed.mapValues { it.value.reversed() }, 310, 5)
        assertEquals(forward, reversed)
        assertEquals(listOf(100L, 200L), forward.getValue("rpm").map { it.receivedAtMonotonicMillis })
        assertEquals(listOf(102L, 202L), forward.getValue("coolant").map { it.receivedAtMonotonicMillis })
    }

    @Test fun noDataInvalidatesDependentWindowsAndLaterReceiptsRecover() {
        val repeatedFuel = fuel.copy(requirement = fuel.requirement.copy(minimumSamples = 2, minimumWindowMillis = 2))
        val dependent = coolant.copy(requirement = coolant.requirement.copy(minimumSamples = 2,
            minimumWindowMillis = 2), invalidatedBy = setOf("fuel-system-status"))
        val current = profile(listOf(repeatedFuel, dependent), gap = 10)
        val secondClock = Clock()
        val second = collector(current, secondClock)
        val action1 = assertNotNull(second.nextAction())
        secondClock.time = 101
        assertTrue(second.complete(action1, observation(repeatedFuel, 101)))
        val action2 = assertNotNull(second.nextAction())
        secondClock.time = 102
        assertTrue(second.complete(action2, observation(dependent, 102)))
        val action3 = assertNotNull(second.nextAction())
        assertEquals("fuel-system-status", action3.signalId)
        secondClock.time = 103
        assertTrue(second.complete(action3, observation(repeatedFuel, 103, noData = true)))
        assertEquals(AcquisitionState.COLLECTING, second.snapshot().state)
        assertTrue(second.snapshot().selectedEvidence.isEmpty())
        // The next complete run must use receipts after NO DATA, including the dependent coolant.
        var time = 104L
        while (second.snapshot().state == AcquisitionState.COLLECTING && time < 120) {
            val action = assertNotNull(second.nextAction())
            secondClock.time = time
            val input = listOf(repeatedFuel, dependent).single { it.requirement.signalId == action.signalId }
            assertTrue(second.complete(action, observation(input, time)))
            time++
        }
        assertEquals(AcquisitionState.EVIDENCE_READY, second.snapshot().state)
        assertTrue(second.snapshot().selectedEvidence.getValue("coolant").all { it.receivedAtMonotonicMillis > 103 })
    }

    @Test fun optionalNoDataCannotInvalidateOrDelayRequiredWindow() {
        val repeated = rpm.copy(requirement = rpm.requirement.copy(minimumSamples = 2,
            minimumWindowMillis = 1))
        val current = profile(listOf(repeated), optional = listOf(coolant), cap = 2)
        val clock = Clock()
        val collector = collector(current, clock)
        completeNext(collector, clock, current.inputs, 101)
        clock.time = 102
        assertTrue(collector.offerExistingOptional("assessment-1", observation(coolant, 102, noData = true)))
        assertEquals(AcquisitionState.COLLECTING, collector.snapshot().state)
        completeNext(collector, clock, current.inputs, 103)
        assertEquals(AcquisitionState.EVIDENCE_READY, collector.snapshot().state)
        assertEquals(listOf(101L, 103L), collector.snapshot().selectedEvidence.getValue("rpm")
            .map { it.receivedAtMonotonicMillis })
    }

    @Test fun profileRejectsBridgeIncompatibleIdentityAndOptionalPrerequisite() {
        assertFailsWith<IllegalArgumentException> {
            profile(listOf(input("engine-speed", "010C", "rpm")))
        }
        assertFailsWith<IllegalArgumentException> {
            profile(listOf(input("rpm", "010C", "rpm", SignalType.INTEGER)))
        }
        assertFailsWith<IllegalArgumentException> {
            profile(listOf(input("runtime", "011F", "s", SignalType.NUMBER)))
        }
        assertFailsWith<IllegalArgumentException> {
            profile(listOf(rpm.copy(invalidatedBy = setOf("coolant"))), optional = listOf(coolant))
        }
        val integer = profile(listOf(input("runtime", "011F", "s", SignalType.INTEGER)))
        assertEquals(SignalType.INTEGER, integer.required.single().requirement.expectedType)
        assertEquals("fuel-system-status", NativeSignalCatalog.definition(0x03, SignalSupport.SUPPORTED).id)
    }

    @Test fun contextChangeKeepsBudgetAndDiscardsOnlyDependentInput() {
        val contextual = coolant.copy(contextKeys = setOf("operation"))
        val current = profile(listOf(rpm.copy(requirement = rpm.requirement.copy(minimumSamples = 2,
            minimumWindowMillis = 10)), contextual.copy(requirement = contextual.requirement.copy(
            minimumSamples = 2, minimumWindowMillis = 10))), cap = 8)
        val clock = Clock()
        val collector = collector(current, clock)
        repeat(3) { index -> completeNext(collector, clock, current.inputs, 101L + index) }
        val before = collector.snapshot().attemptsDispatched
        clock.time = 110
        collector.updateContext(mapOf("operation" to "cruise"))
        val after = collector.snapshot()
        assertEquals(before, after.attemptsDispatched)
        assertEquals(10L, after.elapsedMillis)
        assertTrue(InputQualificationReason.CONTEXT_CHANGED in after.inputs.single { it.signalId == "coolant" }.reasons)
    }

    @Test fun commandAndTimeBoundariesIncludeNoDataAndLateCompletion() {
        val clock = Clock()
        val current = profile(listOf(rpm), cap = 1)
        val collector = collector(current, clock)
        completeNext(collector, clock, current.inputs, 101, noData = true)
        assertEquals(AcquisitionState.PARTIAL, collector.snapshot().state)
        assertEquals(AcquisitionLimitReason.COMMAND_CAP, collector.snapshot().limitReason)
        assertEquals(1, collector.snapshot().attemptsDispatched)
        assertNull(collector.nextAction())

        val lateClock = Clock()
        val late = collector(profile(listOf(rpm), budget = 10), lateClock)
        val action = assertNotNull(late.nextAction())
        lateClock.time = 110
        assertFalse(late.complete(action, observation(rpm, 110)))
        assertEquals(AcquisitionState.PARTIAL, late.snapshot().state)
        assertEquals(AcquisitionLimitReason.TIME_BUDGET, late.snapshot().limitReason)
        assertTrue(late.snapshot().selectedEvidence.isEmpty())
        assertEquals(1, late.snapshot().attemptsDispatched)

        val beforeClock = Clock()
        val before = collector(profile(listOf(rpm), budget = 10), beforeClock)
        beforeClock.time = 110
        assertNull(before.nextAction())
        assertEquals(0, before.snapshot().attemptsDispatched)
        assertEquals(AcquisitionLimitReason.TIME_BUDGET, before.snapshot().limitReason)

        val tickingClock = object : AssessmentClock {
            var time = 99L
            override fun nowMillis() = ++time
        }
        val boundary = AssessmentCollector(profile(listOf(rpm), budget = 3), "boundary", 7,
            ObservationSource.HARDWARE, setOf(0x0C), emptyMap(), tickingClock)
        assertNull(boundary.nextAction()) // deadline arrives between planning and final admission
        assertEquals(0, boundary.snapshot().attemptsDispatched)
        assertEquals(AcquisitionLimitReason.TIME_BUDGET, boundary.snapshot().limitReason)
    }

    @Test fun deadlineReachedBetweenCompletionAdmissionAndCohortSelectionIsPartial() {
        val clock = object : AssessmentClock {
            var time = 100L
            var advanceOnRead = false
            override fun nowMillis() = time.also { if (advanceOnRead) time++ }
        }
        val collector = AssessmentCollector(profile(listOf(rpm), budget = 10), "assessment-1", 7,
            ObservationSource.HARDWARE, setOf(0x0C), emptyMap(), clock)
        val action = assertNotNull(collector.nextAction())
        clock.time = 109
        clock.advanceOnRead = true
        assertTrue(collector.complete(action, observation(rpm, 109)))
        clock.advanceOnRead = false

        val result = collector.snapshot()
        assertEquals(AcquisitionState.PARTIAL, result.state)
        assertEquals(AcquisitionLimitReason.TIME_BUDGET, result.limitReason)
        assertEquals(10L, result.elapsedMillis)
        assertTrue(result.selectedEvidence.isEmpty())
        assertEquals(1, result.attemptsDispatched)
    }

    @Test fun failuresCancellationReplacementAndForeignReceiptsCannotPublish() {
        val clock = Clock()
        val current = profile(listOf(rpm), cap = 3)
        val failed = collector(current, clock)
        val first = assertNotNull(failed.nextAction())
        assertTrue(failed.fail(first, "synthetic protocol failure"))
        assertEquals(AcquisitionState.FAILED, failed.snapshot().state)
        assertNull(failed.nextAction())
        assertFalse(failed.complete(first, observation(rpm, 101)))

        val cancelled = collector(current, clock)
        val oldAction = assertNotNull(cancelled.nextAction())
        cancelled.cancel()
        assertEquals(AcquisitionState.CANCELLED, cancelled.snapshot().state)
        assertFalse(cancelled.complete(oldAction, observation(rpm, 101)))
        val replacement = AssessmentCollector(current, "assessment-2", 7, ObservationSource.HARDWARE,
            setOf(0x0C), emptyMap(), clock)
        assertFalse(replacement.complete(oldAction, observation(rpm, 101)))
        assertEquals(0, replacement.snapshot().attemptsDispatched)

        val foreign = collector(current, clock)
        val action = assertNotNull(foreign.nextAction())
        clock.time = 101
        assertFalse(foreign.complete(action, observation(rpm, 101, source = ObservationSource.SIMULATOR)))
        assertEquals(AcquisitionState.COLLECTING, foreign.snapshot().state)
        assertEquals(setOf(InputQualificationReason.MISSING), foreign.snapshot().inputs.single().reasons)
        val next = assertNotNull(foreign.nextAction())
        clock.time = 102
        assertFalse(foreign.complete(next, observation(rpm, 102, generation = 6)))
        assertEquals(setOf(InputQualificationReason.MISSING), foreign.snapshot().inputs.single().reasons)
    }

    @Test fun verifiedModuleRequirementRejectsUnknownAndDifferentIdentity() {
        val verified = rpm.copy(requirement = rpm.requirement.copy(expectedModule = "verified-ecu"))
        val clock = Clock()
        val collector = collector(profile(listOf(verified), cap = 2), clock)
        val action = assertNotNull(collector.nextAction())
        clock.time = 101
        assertTrue(collector.complete(action, observation(verified, 101)))
        assertTrue(InputQualificationReason.IDENTITY_MISMATCH in collector.snapshot().inputs.single().reasons)
        val next = assertNotNull(collector.nextAction())
        clock.time = 102
        assertTrue(collector.complete(next, observation(verified, 102, module = "other-ecu")))
        assertEquals(AcquisitionState.PARTIAL, collector.snapshot().state)
        assertTrue(collector.snapshot().selectedEvidence.isEmpty())
    }

    @Test fun contextChangeDuringPendingReadDiscardsItsLateReceipt() {
        val contextual = rpm.copy(contextKeys = setOf("operation"))
        val clock = Clock()
        val collector = collector(profile(listOf(contextual)), clock)
        val action = assertNotNull(collector.nextAction())
        clock.time = 101
        collector.updateContext(mapOf("operation" to "cruise"))
        clock.time = 102
        assertFalse(collector.complete(action, observation(contextual, 102)))
        assertEquals(AcquisitionState.COLLECTING, collector.snapshot().state)
        assertTrue(collector.snapshot().selectedEvidence.isEmpty())
        assertEquals(1, collector.snapshot().attemptsDispatched)
        val replacement = assertNotNull(collector.nextAction())
        clock.time = 103
        assertFalse(collector.complete(replacement, observation(contextual, 101)))
        val fresh = assertNotNull(collector.nextAction())
        clock.time = 104
        assertTrue(collector.complete(fresh, observation(contextual, 104)))
        assertEquals(AcquisitionState.EVIDENCE_READY, collector.snapshot().state)
    }

    @Test fun missingRequiredCapabilityDefersWithoutSpendingAnOptionalCommand() {
        val clock = Clock()
        val collector = collector(profile(listOf(fuel), optional = listOf(rpm)), clock, advertised = setOf(0x0C))
        assertNull(collector.nextAction())
        assertEquals(AcquisitionState.DEFERRED, collector.snapshot().state)
        assertEquals(0, collector.snapshot().attemptsDispatched)
        assertTrue(InputQualificationReason.NOT_ADVERTISED in collector.snapshot().inputs.first().reasons)
    }
    @Test
    fun fullExchangeAllowanceAtExactDeadlineDefersWithoutSpendingCommand() {
        for (allowance in listOf(3_199L, 3_200L)) {
            val clock = Clock()
            val collector = collector(profile(listOf(rpm), budget = 3_200), clock)
            val action = collector.nextAction(allowance)
            if (allowance == 3_200L) {
                assertNull(action)
                assertEquals(AcquisitionState.PARTIAL, collector.snapshot().state)
                assertEquals(AcquisitionLimitReason.TIME_BUDGET, collector.snapshot().limitReason)
                assertEquals(0, collector.snapshot().attemptsDispatched)
            } else {
                assertNotNull(action)
                assertEquals(1, collector.snapshot().attemptsDispatched)
            }
        }
    }

    @Test
    fun passiveRepeatedCohortKeepsEveryRequiredReceiptWithZeroCommands() {
        val clock = Clock()
        val repeatedRpm = rpm.copy(requirement = rpm.requirement.copy(minimumSamples = 2))
        val current = profile(listOf(repeatedRpm, coolant), gap = 10)
        val collector = AssessmentCollector(current, "passive-repeated", 7, ObservationSource.HARDWARE,
            setOf(5, 12), emptyMap(), clock, AssessmentCollectionMode.PASSIVE)
        for ((input, time) in listOf(rpm to 101L, coolant to 102L, rpm to 105L, coolant to 106L)) {
            clock.time = time
            assertTrue(collector.offerPassive("passive-repeated", observation(input, time)))
        }
        val result = collector.snapshot()
        assertEquals(AcquisitionState.EVIDENCE_READY, result.state)
        assertEquals(0, result.attemptsDispatched)
        assertEquals(listOf(101L, 105L), result.selectedEvidence.getValue("rpm").map { it.receivedAtMonotonicMillis })
        assertEquals(listOf(102L, 106L), result.selectedEvidence.getValue("coolant").map { it.receivedAtMonotonicMillis })
        assertNull(collector.nextAction())
    }

    @Test
    fun passiveFastRpmRetainsFreshEvidenceAcrossTwoSecondCoolantSamples() {
        val clock = Clock()
        val fast = rpm.copy(requirement = rpm.requirement.copy(minimumSamples = 2,
            minimumWindowMillis = 100, maximumAgeMillis = 5_000))
        val slow = coolant.copy(requirement = coolant.requirement.copy(minimumSamples = 2,
            minimumWindowMillis = 100, maximumAgeMillis = 5_000))
        val current = profile(listOf(fast, slow), budget = 10_000, gap = 1_000)
        val collector = AssessmentCollector(current, "mixed-rates", 7, ObservationSource.HARDWARE,
            setOf(5, 12), emptyMap(), clock, AssessmentCollectionMode.PASSIVE)
        collector.offerPassive("mixed-rates", observation(slow, 100))
        for (time in 100L..2_100L step 200) {
            clock.time = time
            collector.offerPassive("mixed-rates", observation(fast, time))
        }
        assertEquals(AcquisitionState.COLLECTING, collector.snapshot().state)
        collector.offerPassive("mixed-rates", observation(slow, 2_100))
        val result = collector.snapshot()
        assertEquals(AcquisitionState.EVIDENCE_READY, result.state)
        assertEquals(0, result.attemptsDispatched)
        val selected = result.selectedEvidence.getValue("rpm")
        assertEquals(2, selected.size)
        assertTrue(selected.first().receivedAtMonotonicMillis <= 1_100)
        assertTrue(selected.last().receivedAtMonotonicMillis >= 1_100)
        assertTrue(selected.all { (it.receivedAtMonotonicMillis - 100) % 200 == 0L })
    }

    @Test
    fun passiveTimeSpanningHistoryExpiresOldAnchorsAndRespectsNoDataResets() {
        for (invalidate in listOf(false, true)) {
            val clock = Clock()
            val age = if (invalidate) 5_000L else 2_000L
            val fast = rpm.copy(requirement = rpm.requirement.copy(minimumSamples = 2,
                minimumWindowMillis = 100, maximumAgeMillis = age), invalidatedBy = setOf("coolant"))
            val slow = coolant.copy(requirement = coolant.requirement.copy(minimumSamples = 2,
                minimumWindowMillis = 100, maximumAgeMillis = age))
            val current = profile(listOf(fast, slow), budget = 10_000, gap = 1_000)
            val collector = AssessmentCollector(current, "anchors", 7, ObservationSource.HARDWARE,
                setOf(5, 12), emptyMap(), clock, AssessmentCollectionMode.PASSIVE)
            collector.offerPassive("anchors", observation(slow, 100))
            for (time in 100L..4_100L step 200) {
                clock.time = time
                if (invalidate && time == 2_100L)
                    collector.offerPassive("anchors", observation(slow, time, noData = true))
                collector.offerPassive("anchors", observation(fast, time))
            }
            collector.offerPassive("anchors", observation(slow, 4_100))
            assertEquals(AcquisitionState.COLLECTING, collector.snapshot().state,
                "The old coolant receipt was ${if (invalidate) "invalidated" else "expired"}")
            for (time in 4_300L..6_100L step 200) {
                clock.time = time
                collector.offerPassive("anchors", observation(fast, time))
            }
            collector.offerPassive("anchors", observation(slow, 6_100))
            val result = collector.snapshot()
            assertEquals(AcquisitionState.EVIDENCE_READY, result.state)
            assertEquals(listOf(4_100L, 6_100L), result.selectedEvidence.getValue("coolant")
                .map { it.receivedAtMonotonicMillis })
            assertTrue(result.selectedEvidence.values.flatten().all {
                it.receivedAtMonotonicMillis > 2_100 && 6_100 - it.receivedAtMonotonicMillis <= age
            })
            assertEquals(0, result.attemptsDispatched)
        }
    }

    @Test
    fun passiveRequiredReceiptsUseSameCohortReasonsWithoutDispatch() {
        val clock = Clock()
        val collector = AssessmentCollector(profile(required = listOf(rpm, coolant), gap = 20),
            "passive", 7, ObservationSource.HARDWARE, setOf(0x0C, 0x05), emptyMap(), clock,
            AssessmentCollectionMode.PASSIVE)
        assertNull(collector.nextAction())
        assertFalse(collector.offerPassive("foreign", observation(rpm, 100)))
        assertFalse(collector.offerPassive("passive", observation(rpm, 100, generation = 8)))
        assertTrue(collector.offerPassive("passive", observation(rpm, 100, noData = true)))
        assertTrue(InputQualificationReason.NO_DATA in collector.snapshot().inputs.first().reasons)
        clock.time = 110
        assertTrue(collector.offerPassive("passive", observation(rpm, 110)))
        clock.time = 140
        assertTrue(collector.offerPassive("passive", observation(coolant, 140)))
        assertEquals(AcquisitionState.COLLECTING, collector.snapshot().state)
        assertTrue(collector.snapshot().inputs.all { InputQualificationReason.INCOMPATIBLE_COHORT in it.reasons })
        clock.time = 145
        assertTrue(collector.offerPassive("passive", observation(rpm, 145)))
        val result = collector.snapshot()
        assertEquals(AcquisitionState.EVIDENCE_READY, result.state)
        assertEquals(0, result.attemptsDispatched)
        assertEquals(listOf(145L), result.selectedEvidence.getValue("rpm").map { it.receivedAtMonotonicMillis })
        assertEquals(listOf(140L), result.selectedEvidence.getValue("coolant").map { it.receivedAtMonotonicMillis })
    }

    @Test
    fun passiveRejectsIncompatibleReceiptClocksAndCannotPublishAtDeadline() {
        val clock = Clock()
        val collector = AssessmentCollector(profile(required = listOf(rpm), budget = 100),
            "passive", 7, ObservationSource.HARDWARE, setOf(0x0C), emptyMap(), clock,
            AssessmentCollectionMode.PASSIVE)
        for (time in listOf(99L, 999L)) {
            assertTrue(collector.offerPassive("passive", observation(rpm, time)))
            assertTrue(InputQualificationReason.IDENTITY_MISMATCH in collector.snapshot().inputs.single().reasons)
            assertTrue(collector.snapshot().selectedEvidence.isEmpty())
            clock.time++
        }
        clock.time = 200
        assertFalse(collector.offerPassive("passive", observation(rpm, 200)))
        assertEquals(AcquisitionState.PARTIAL, collector.snapshot().state)
        assertEquals(AcquisitionLimitReason.TIME_BUDGET, collector.snapshot().limitReason)
        assertEquals(0, collector.snapshot().attemptsDispatched)
    }

    @Test
    fun passiveCannotQualifyUnadvertisedOptionalReceipt() {
        val clock = Clock()
        val collector = AssessmentCollector(profile(required = listOf(rpm), optional = listOf(coolant)),
            "passive", 7, ObservationSource.HARDWARE, setOf(0x0C), emptyMap(), clock,
            AssessmentCollectionMode.PASSIVE)
        assertFalse(collector.offerPassive("passive", observation(coolant, 100)))
        assertTrue(collector.offerPassive("passive", observation(rpm, 100)))
        assertEquals(setOf("rpm"), collector.snapshot().selectedEvidence.keys)
        assertTrue(InputQualificationReason.NOT_ADVERTISED in collector.snapshot().inputs.last().reasons)
        assertEquals(0, collector.snapshot().attemptsDispatched)
    }

}
