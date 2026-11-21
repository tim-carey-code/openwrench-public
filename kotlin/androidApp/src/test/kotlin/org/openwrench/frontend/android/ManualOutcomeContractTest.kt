package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*
import org.openwrench.nativecore.ScanOrigin
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Entirely fictional retained fixtures; never opens a transport. */
class ManualOutcomeContractTest {
    private fun base(): SavedInvestigation {
        val scan = caseScanFixture().let { it.copy(capture = it.capture!!.copy(
            origin = ScanOrigin.SYNTHETIC, sessionId = null, sourceEcu = null)) }
        val c = DiagnosticCaseBuilder.create(scan, caseDriveFixture(), true, 4_000)
        var j = DiagnosticInvestigation("fictional", listOf(InvestigationRevision("complaint", 1, 5000,
            "Fictional author", "Reported story", InvestigationStep.Complaint("Fictional cold-start story; approximately -16% reported, not verified"))))
        fun add(id: String, s: InvestigationStep) { j = j.append(id, 6000, "Fictional author", "Synthetic fixture", s) }
        add("starting", InvestigationQuestionCatalog.startingReport(StartingBehavior.UNKNOWN, "Fictional reported unknown"))
        add("timing", InvestigationQuestionCatalog.timingReport(SymptomTiming.UNKNOWN, "Fictional reported unknown"))
        add("adaptation", InvestigationQuestionCatalog.adaptationReport(AdaptationHistory.UNKNOWN, "Fictional reset history unknown"))
        add("manual", InvestigationStep.Observation(InvestigationObservationKind.MANUALLY_OBSERVED, "Fictional click reported", "Synthetic only; no sealing measurement"))
        add("h1", InvestigationStep.Hypothesis("Unwanted vapor flow is a candidate, not a diagnosis", listOf("manual")))
        add("h2", InvestigationStep.Hypothesis("Input or other fueling explanation remains open", listOf("starting")))
        add("q1", InvestigationQuestionCatalog.question(InvestigationQuestionId.VAPOR_SEALING).proposal(listOf("h1", "h2")))
        add("q2", InvestigationQuestionCatalog.question(InvestigationQuestionId.VAPOR_SEALING).proposal(listOf("h1", "h2")))
        add("old", InvestigationStep.TestResult("q1", InvestigationOutcome.INCONCLUSIVE, "normal -16% in prose is not a typed result",
            "Fictional unknown method", listOf("manual"), emptyList()))
        return SavedInvestigation(j, listOf(c))
    }
    private fun contract(): ManualOutcomeContract {
        val b = base()
        val c = b.cases.single()
        val unknown = ReportedDetail(ReportKnowledge.UNKNOWN)
        val r = AttributedManualOutcome("typed1", b.journal.revisions.size + 1, 7000, "Fictional author", "Correct prior account",
            ManualOutcomeContext("q1", c.id, OutcomeCatalogReference(InvestigationQuestionId.VAPOR_SEALING, 1),
                OwnerReportSelection(ReportSelection.SELECTED, "starting"), OwnerReportSelection(ReportSelection.SELECTED, "timing"),
                listOf("manual"), listOf(InvestigationEvidenceRef(c.id, InvestigationObservationKind.CASE_SCAN_READING, 5)),
                OwnerReportSelection(ReportSelection.SELECTED, "adaptation")),
            ManualOutcomeOrigin.SYNTHETIC, ManualFaultMode.UNWANTED_FLOW_WHEN_EXPECTED_SEALED, ManualOutcomeMethod.CLICK_OR_ACKNOWLEDGMENT,
            DeclaredManualOutcome.INCONCLUSIVE, unknown, ReportedDetail(ReportKnowledge.REPORTED, "Fictional click account"),
            unknown, unknown, ReportedApplicability.UNKNOWN, null, "Fictional fixture, not a vehicle record",
            listOf(AttributedManualValue(-16.0, ManualValueUnit.OTHER_REPORTED, "reported percent; not OBD")),
            listOf(InvestigationEffect("h1", HypothesisEffect.UNCHANGED, "Click cannot establish sealing")), "old")
        return ManualOutcomeContract(" \n" + InvestigationCodec.encode(b) + "\n ", listOf(r))
    }
    private fun edit(c: ManualOutcomeContract = contract(), f: (AttributedManualOutcome) -> AttributedManualOutcome) = c.copy(outcomes = listOf(f(c.outcomes.single())))
    private fun encoded() = Json.parseToJsonElement(ManualOutcomeContractCodec.encode(contract())).jsonObject
    private fun rootChange(key: String, value: JsonElement) = JsonObject(encoded() + (key to value)).toString()
    private fun rowChange(index: Int, value: JsonElement): String {
        val root = encoded(); val row = root.getValue("outcomes").jsonArray.single().jsonArray.toMutableList()
        row[index] = value
        return JsonObject(root + ("outcomes" to JsonArray(listOf(JsonArray(row))))).toString()
    }

    @Test fun reviewRoundTripPreservesEveryFieldAndExactHistoricBytes() {
        val c = contract(); val text = ManualOutcomeContractCodec.encode(c)
        assertEquals(c, ManualOutcomeContractCodec.decode(text))
        assertEquals(c.legacyJson, ManualOutcomeContractCodec.decode(text).legacyJson)
        assertEquals(c.legacySnapshot(), ManualOutcomeContractCodec.decode(text).legacySnapshot())
        assertEquals(ManualScopeUse.SYNTHETIC_ONLY, c.outcomes.single().scopeAssessment().use)
        assertEquals(c.legacySnapshot().cases, ManualOutcomeContractCodec.decode(text).legacySnapshot().cases)
    }
    @Test fun explicitLegacyReadDoesNotInterpretProseOrMigrateAnything() {
        val original = contract().legacyJson
        val c = ManualOutcomeContractCodec.readLegacy(original)
        assertEquals(original, c.legacyJson); assertTrue(c.outcomes.isEmpty())
        assertEquals(mapOf("q1" to "old"), c.activeResultIds())
        assertFails { InvestigationCodec.decode(ManualOutcomeContractCodec.encode(c)) }
        assertFails { ManualOutcomeContractCodec.readLegacy(original.replace("openwrench.investigation/1", "openwrench.investigation/2")) }
    }
    @Test fun realTypedUnitsNullsUnknownsAndDeclaredResultsRoundTripWithoutQualification() {
        val c = contract()
        for (origin in ManualOutcomeOrigin.entries) for (method in ManualOutcomeMethod.entries) for (declared in DeclaredManualOutcome.entries) {
            val changed = edit(c) { it.copy(origin = origin, method = method, declaredOutcome = declared,
                observedAtEpochMillis = 6500, values = listOf(AttributedManualValue(0.0, ManualValueUnit.KPA))) }
            val decoded = ManualOutcomeContractCodec.decode(ManualOutcomeContractCodec.encode(changed))
            assertEquals(changed, decoded)
            assertEquals(c.legacySnapshot(), decoded.legacySnapshot()) // manual zero/-16 never changes OBD evidence
        }
    }
    @Test fun correctionsRequireActivePredecessorOfExactQuestionAndPreserveBothQuestions() {
        val c = contract(); val a = c.outcomes.single()
        val q2 = a.copy(id = "typed2", sequence = a.sequence + 1, context = a.context.copy(questionRevisionId = "q2"), supersedesResultId = null)
        val corrected = a.copy(id = "typed3", sequence = a.sequence + 2, supersedesResultId = a.id)
        val next = c.copy(outcomes = listOf(a, q2, corrected))
        assertEquals(mapOf("q1" to "typed3", "q2" to "typed2"), ManualOutcomeContractCodec.decode(ManualOutcomeContractCodec.encode(next)).activeResultIds())
        for (bad in listOf<String?>(null, "old", "typed2", "future")) assertFails { next.copy(outcomes = listOf(a, q2, corrected.copy(supersedesResultId = bad))).validate() }
        assertEquals(listOf("typed1", "typed2", "typed3"), next.outcomes.map { it.id })
    }
    @Test fun missingWrongTypeAndFutureReferencesFailClosed() {
        val c = contract(); val r = c.outcomes.single()
        for (q in listOf("missing", "manual", "typed1")) assertFails { edit(c) { it.copy(context = it.context.copy(questionRevisionId = q)) }.validate() }
        for (id in listOf("missing", "h1", "typed1")) assertFails { edit(c) { it.copy(context = it.context.copy(observationIds = listOf(id))) }.validate() }
        for (id in listOf("missing", "manual", "timing")) assertFails { edit(c) { it.copy(context = it.context.copy(startingReport = OwnerReportSelection(ReportSelection.SELECTED, id))) }.validate() }
        assertFails { edit(c) { it.copy(context = it.context.copy(caseId = "missing", retainedEvidence = emptyList())) }.validate() }
        assertFails { edit(c) { it.copy(authorEffects = listOf(InvestigationEffect("missing", HypothesisEffect.UNCHANGED, "Unknown"))) }.validate() }
        assertFails { edit(c) { it.copy(context = it.context.copy(adaptationReport = OwnerReportSelection(ReportSelection.SELECTED, "starting"))) }.validate() }
        assertFails { edit(c) { it.copy(id = "old") }.validate() }
        assertFails { edit(c) { it.copy(sequence = r.sequence + 1) }.validate() }
        assertFails { edit(c) { it.copy(context = it.context.copy(observationIds = listOf("manual", "manual"))) }.validate() }
    }
    @Test fun absentUnmeasuredBankAndReportedEvidenceCannotBecomeRetainedMeasurements() {
        val c = contract(); val ref = c.outcomes.single().context.retainedEvidence.single()
        val invalid = listOf(ref.copy(pid = 12), ref.copy(pid = 255), ref.copy(caseId = "other"), ref.copy(findingIndex = 0),
            ref.copy(kind = InvestigationObservationKind.REPORTED), ref.copy(kind = InvestigationObservationKind.MANUALLY_OBSERVED),
            ref.copy(kind = InvestigationObservationKind.CASE_FUEL_INTERVAL, pid = null, findingIndex = 0, intervalIndex = 999))
        for (bad in invalid) assertFails { edit(c) { it.copy(context = it.context.copy(retainedEvidence = listOf(bad))) }.validate() }
        val b = c.legacySnapshot(); val case = b.cases.single()
        val finding = case.drive.review.assessment!!.findings.indexOfFirst { it.fuelIntervals.isNotEmpty() }
        assertTrue(finding >= 0)
        val good = ref.copy(kind = InvestigationObservationKind.CASE_FUEL_INTERVAL, pid = null, findingIndex = finding, intervalIndex = 0)
        edit(c) { it.copy(context = it.context.copy(retainedEvidence = listOf(good))) }.validate()
        assertFails { edit(c) { it.copy(context = it.context.copy(retainedEvidence = listOf(good, good))) }.validate() }
    }
    @Test fun catalogBindingMustMatchActualQuestionAndUnsupportedActuatorCannotBeAttributed() {
        val c = contract(); val b = c.legacySnapshot()
        fun changedTest(test: InvestigationStep.ProposedTest): ManualOutcomeContract {
            val j = b.journal.copy(revisions = b.journal.revisions.map { if (it.id == "q1") it.copy(step = test) else it })
            return c.copy(legacyJson = InvestigationCodec.encode(b.copy(journal = j)))
        }
        val q = b.journal.revisions.single { it.id == "q1" }.step as InvestigationStep.ProposedTest
        assertFails { changedTest(q.copy(question = "Custom unrelated question")).validate() }
        assertFails { changedTest(q.copy(kind = InvestigationTestKind.ACTUATOR_UNSUPPORTED)).validate() }
        val custom = changedTest(q.copy(question = "Custom question with explicit unknown applicability"))
        edit(custom) { it.copy(context = it.context.copy(catalog = null)) }.validate()
    }
    @Test fun strictJsonRejectsUnknownVersionFieldsEnumsDuplicateKeysTypesAndRows() {
        assertFails { ManualOutcomeContractCodec.decode(rootChange("schema", JsonPrimitive("openwrench.investigation/2"))) }
        assertFails { ManualOutcomeContractCodec.decode(rootChange("extra", JsonNull)) }
        assertFails { ManualOutcomeContractCodec.decode(rootChange("legacy_json", JsonNull)) }
        assertFails { ManualOutcomeContractCodec.decode("{\"schema\":\"duplicate\"," + ManualOutcomeContractCodec.encode(contract()).drop(1)) }
        for ((i, v) in listOf(1 to JsonPrimitive("10"), 1 to JsonPrimitive(10.5), 1 to JsonPrimitive(Long.MAX_VALUE),
            2 to JsonPrimitive(true), 6 to JsonPrimitive("VERIFIED"), 15 to JsonPrimitive("6500"), 19 to JsonPrimitive(false)))
            assertFails { ManualOutcomeContractCodec.decode(rowChange(i, v)) }
        assertFails { ManualOutcomeContractCodec.decode(rootChange("outcomes", JsonArray(listOf(JsonArray(emptyList()))))) }
        val tooMany = JsonArray(List(501) { encoded().getValue("outcomes").jsonArray.single() })
        assertFails { ManualOutcomeContractCodec.decode(rootChange("outcomes", tooMany)) }
    }
    @Test fun sharedRevisionQuotaIncludesFrozenAndTypedHistory() {
        val c = contract(); val r = c.outcomes.single(); val b = c.legacySnapshot()
        val tail = (1..(500 - b.journal.revisions.size)).map { index -> r.copy(id = "typed$index", sequence = b.journal.revisions.size + index,
            supersedesResultId = if (index == 1) "old" else "typed${index - 1}") }
        c.copy(outcomes = tail).validate()
        assertFails { c.copy(outcomes = tail + r.copy(id = "overflow", sequence = 500)).validate() }
        assertFails { ManualOutcomeContractCodec.decode(" ".repeat(InvestigationCodec.MAX_BYTES + 1)) }
    }
    @Test fun legacyStoreRejectsReviewAndFutureFormatsWithoutRewritingBytes() {
        val root = Files.createTempDirectory("ow-contract-fictional").toFile()
        try {
            val store = InvestigationStore(root); val b = base()
            store.create(b.copy(journal = b.journal.copy(revisions = b.journal.revisions.take(1))))
            b.journal.revisions.drop(1).forEachIndexed { index, r ->
                store.append(b.journal.id, b.journal.revisions[index].id, r.id, r.recordedAtEpochMillis, r.author, r.reason, r.step)
            }
            val file = File(root, "investigations-v1/fictional.json")
            val original = file.readBytes()
            ManualOutcomeContractCodec.decode(ManualOutcomeContractCodec.encode(contract()))
            assertContentEquals(original, file.readBytes())
            assertEquals(b, InvestigationStore(root).load("fictional"))
            for (unsupported in listOf(ManualOutcomeContractCodec.encode(contract()), String(original, Charsets.UTF_8).replace("openwrench.investigation/1", "openwrench.investigation/2"))) {
                file.writeText(unsupported)
                assertFails { InvestigationStore(root).load("fictional") }
                assertFails { InvestigationStore(root).append("fictional", "old", "attempt", 9000, "Fictional", "No write", InvestigationStep.Complaint("No migration")) }
                assertEquals(unsupported, file.readText())
            }
        } finally { root.deleteRecursively() }
    }
}
