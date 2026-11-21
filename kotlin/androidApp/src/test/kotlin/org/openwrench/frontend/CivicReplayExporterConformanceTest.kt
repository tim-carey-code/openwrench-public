package org.openwrench.frontend

import org.openwrench.core.SignalValue
import kotlin.test.*

/**
 * Frozen consumer conformance for the historical Python exporter
 * (`tools/civic_replay.py::sanitize_source_capture`, PR #57, e8d674a).
 * Inputs are SYNTHETIC; none is a real Civic capture or physical qualification.
 * The shared fixture and verbatim exporter examples run entirely in Kotlin.
 */
class CivicReplayExporterConformanceTest {
    private val first = "2026-01-01T00:00:00Z"
    private val second = "2026-01-01T00:00:01Z"
    private val third = "2026-01-01T00:00:02Z"
    private val provenance = "SYNTHETIC (source-declared; not independently verified). Client receipt offsets are first " +
        "client monotonic receipt, not backend completion. Polling interval and HTTP latency delay visibility; integer " +
        "source age truncates below 1 ms. Cache changes entirely between polls cannot be recovered. " +
        "No exact ECU or read time is claimed."

    private val verbatimExport = """{"schema":"openwrench.civic-replay/1","capture":{"origin":"hardware","vehicle_label":"Honda Civic","captured_at_utc":"2026-01-01T00:00:00Z","clock_basis":"client_receipt","provenance":"$provenance"},"duration_ms":2000,"signals":[{"id":"rpm","pid":"010C","label":"Engine speed","unit":"rpm","value_type":"number","support":"supported"}],"observations":[{"at_ms":100,"signal":"rpm","availability":"available","value":0,"observed_at_utc":"2026-01-01T00:00:00Z","age_at_capture_ms":200,"reason":null},{"at_ms":800,"signal":"rpm","availability":"available","value":0,"observed_at_utc":"2026-01-01T00:00:01Z","age_at_capture_ms":80,"reason":null},{"at_ms":1100,"signal":"rpm","availability":"available","value":0,"observed_at_utc":"2026-01-01T00:00:02Z","age_at_capture_ms":20,"reason":null}]}"""

    // id, pid, label, unit, value_type: CANONICAL and _SAFE_LABELS in the exporter.
    private val canonical = listOf(
        listOf("load", "0104", "Calculated engine load", "%", "number"),
        listOf("rpm", "010C", "Engine speed", "rpm", "number"),
        listOf("coolant", "0105", "Engine coolant temperature", "\u00b0C", "number"),
        listOf("stft", "0106", "Short-term fuel trim, Bank 1", "%", "number"),
        listOf("ltft", "0107", "Long-term fuel trim, Bank 1", "%", "number"),
        listOf("map", "010B", "Intake manifold absolute pressure", "kPa", "number"),
        listOf("speed", "010D", "Vehicle speed", "km/h", "number"),
        listOf("intake-air", "010F", "Intake air temperature", "\u00b0C", "number"),
        listOf("maf", "0110", "Mass air flow", "g/s", "number"),
        listOf("throttle", "0111", "Absolute throttle position", "%", "number"),
        listOf("runtime", "011F", "Engine runtime since start", "s", "integer"),
    )

    private fun signal(item: List<String>) =
        """{"id":"${item[0]}","pid":"${item[1]}","label":"${item[2]}","unit":"${item[3]}","value_type":"${item[4]}","support":"supported"}"""

    private fun event(at: Long, id: String, value: String, observed: String?, age: Long?, state: String = "available", reason: String? = null) =
        """{"at_ms":$at,"signal":"$id","availability":"$state","value":$value,"observed_at_utc":${observed?.let { "\"$it\"" }},"age_at_capture_ms":$age,"reason":${reason?.let { "\"$it\"" }}}"""

    private fun export(signals: List<String>, events: List<String>, duration: Long = 3000) =
        """{"schema":"openwrench.civic-replay/1","capture":{"origin":"hardware","vehicle_label":"Honda Civic","captured_at_utc":"$first","clock_basis":"client_receipt","provenance":"$provenance"},"duration_ms":$duration,"signals":[${signals.joinToString(",")}],"observations":[${events.joinToString(",")}]}"""

    private fun rpmOnly(vararg events: String, duration: Long = 3000) = export(listOf(signal(canonical[1])), events.toList(), duration)

    private fun row(recording: CivicRecording, at: Long) = CivicReplayState(recording = recording, positionMillis = at,
        observations = recording.observations.takeWhile { it.atMillis <= at }).dashboardSignals().single()

    @Test fun sharedSyntheticFixturePreservesAllSignalsFreshnessAndNoDataRecovery() {
        val recording = requireNotNull(javaClass.getResourceAsStream("/replay/synthetic-civic-replay.json")).use {
            assertIs<ReplayLoadResult.Loaded>(readCivicRecording(it)).recording
        }
        assertEquals(11, recording.catalog.size)
        assertEquals(24, recording.observations.size)
        assertTrue(recording.capture.provenance.startsWith("SYNTHETIC (source-declared; not independently verified)"))
        assertEquals("client_receipt", recording.capture.clockBasis)
        fun rows(at: Long) = CivicReplayState(recording = recording, positionMillis = at,
            observations = recording.observations.takeWhile { it.atMillis <= at }).dashboardSignals().associateBy { it.definition.id }
        assertTrue(rows(99).values.all { it.availability == SignalAvailability.NOT_SAMPLED && it.value == null })
        assertEquals(SignalAvailability.AVAILABLE, rows(100).getValue("speed").availability)
        assertEquals(4999L, rows(100).getValue("speed").ageMillis)
        assertEquals(SignalAvailability.STALE, rows(101).getValue("speed").availability)
        assertEquals(SignalAvailability.STALE, rows(800).getValue("speed").availability)
        assertEquals(SignalValue.Number(824.0), rows(1099).getValue("rpm").value)
        val unavailable = rows(1100).getValue("rpm")
        assertEquals(SignalAvailability.UNAVAILABLE, unavailable.availability)
        assertNull(unavailable.value)
        assertNull(unavailable.observedAt)
        assertNull(unavailable.ageMillis)
        assertContains(unavailable.reason, "NO DATA")
        assertEquals(SignalValue.Number(824.0), rows(1400).getValue("rpm").value)
        assertEquals("2026-01-01T00:00:02Z", rows(1400).getValue("rpm").observedAt)
        assertEquals(200L, rows(1400).getValue("rpm").ageMillis)
        assertEquals(SignalValue.Integer(120), rows(1400).getValue("runtime").value)
    }

    @Test fun verbatimExporterOutputLoadsWithReceiptTimingAndSyntheticProvenance() {
        val recording = assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(verbatimExport)).recording
        assertEquals("client_receipt", recording.capture.clockBasis)
        assertEquals("Honda Civic", recording.capture.vehicleLabel)
        assertEquals(first, recording.capture.capturedAtUtc)
        assertTrue(recording.capture.provenance.startsWith("SYNTHETIC"))
        assertEquals(2000L, recording.durationMillis)
        assertEquals(listOf(100L, 800L, 1100L), recording.observations.map { it.atMillis })
        assertEquals(listOf(200L, 80L, 20L), recording.observations.map { it.ageAtCaptureMillis })
        assertEquals(listOf(first, second, third), recording.observations.map { it.observedAtUtc })
        // Equal values with distinct observation identities remain separate observations.
        assertTrue(recording.observations.all { it.value == SignalValue.Number(0.0) })
        // Source age is counted once and advances only with time since receipt.
        assertEquals(SignalAvailability.NOT_SAMPLED, row(recording, 99).availability)
        assertEquals(200L, row(recording, 100).ageMillis)
        assertEquals(600L, row(recording, 500).ageMillis)
        assertEquals(80L, row(recording, 800).ageMillis)
        assertEquals(second, row(recording, 800).observedAt)
        assertEquals(20L, row(recording, 1100).ageMillis)
        assertEquals(third, row(recording, 1999).observedAt)
        assertEquals(SignalValue.Number(0.0), row(recording, 1999).value)
    }

    @Test fun allElevenCanonicalSignalsLoadWithExporterLabelsUnitsAndTypes() {
        val events = canonical.flatMapIndexed { index, item ->
            val value = { n: Int -> if (item[4] == "integer") "$n" else "$n.5" }
            listOf(event(index * 20L, item[0], value(5), first, 10), event(index * 20L + 10, item[0], value(6), second, 0))
        }
        val recording = assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(export(canonical.map(::signal), events))).recording
        assertEquals(canonical.map { it[0] }, recording.catalog.map { it.id })
        assertEquals(canonical.map { it[1] }, recording.catalog.map { it.pid })
        assertEquals(canonical.map { it[3] }, recording.catalog.map { it.unit })
        assertEquals(SignalValue.Integer(6), recording.observations.last().value)
        val rows = CivicReplayState(recording = recording, positionMillis = 2999, observations = recording.observations).dashboardSignals()
        assertEquals(11, rows.size)
        assertTrue(rows.all { it.availability == SignalAvailability.AVAILABLE && it.observedAt == second })
    }

    @Test fun signalsOutsideTheAgreedElevenAndMismatchedIdentitiesAreRejected() {
        for (outside in listOf(listOf("fuel-level", "012F", "Fuel tank level", "%", "number"),
            listOf("voltage", "0142", "Control module voltage", "V", "number"))) {
            assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(export(listOf(signal(outside)),
                listOf(event(0, outside[0], "1", first, 0), event(10, outside[0], "2", second, 0)))), outside.toString())
        }
        for (item in canonical) {
            val index = canonical.indexOf(item)
            val wrongPid = item.toMutableList().also { it[1] = "01FF" }
            val wrongUnit = item.toMutableList().also { it[3] = "bad" }
            val wrongType = item.toMutableList().also { it[4] = if (item[4] == "integer") "number" else "integer" }
            val otherPid = item.toMutableList().also { it[1] = canonical[(index + 1) % canonical.size][1] }
            for (bad in listOf(wrongPid, wrongUnit, wrongType, otherPid)) {
                assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(export(listOf(signal(bad)),
                    listOf(event(0, item[0], "1", first, 0), event(10, item[0], "1", first, 0)))), bad.toString())
            }
        }
    }

    @Test fun exporterBoundaryOffsetsTimestampsAndAgesLoad() {
        val nanos = "2026-01-01T00:00:00.123456789Z"
        val recording = assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(rpmOnly(
            event(0, "rpm", "0", nanos, Long.MAX_VALUE),
            event(1, "rpm", "1", nanos, 0),
            event(2999, "rpm", "2", first, 4999),
            duration = 3000))).recording
        assertEquals(nanos, recording.observations.first().observedAtUtc)
        assertEquals(Long.MAX_VALUE, recording.observations.first().ageAtCaptureMillis)
        val maximum = row(recording, 0)
        assertEquals(SignalAvailability.STALE, maximum.availability)
        assertNull(maximum.value)
        assertEquals(Long.MAX_VALUE, maximum.ageMillis)
        assertEquals(SignalAvailability.AVAILABLE, row(recording, 2999).availability)
        assertEquals(4999L, row(recording, 2999).ageMillis)
        // One past the exporter's signed-64-bit age limit is not loadable.
        assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(verbatimExport.replace("\"age_at_capture_ms\":200", "\"age_at_capture_ms\":9223372036854775808")))
    }

    @Test fun exporterObservedNoDataExpiredAndRecoveryTransitionsReplay() {
        val recording = assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(rpmOnly(
            event(0, "rpm", "700", first, 20),
            event(500, "rpm", "null", null, null, "unavailable", "ECU returned NO DATA"),
            event(1000, "rpm", "700", second, 5),
            event(1500, "rpm", "null", second, 5000, "stale", "Observation expired"),
        ))).recording
        assertEquals(SignalAvailability.AVAILABLE, row(recording, 499).availability)
        val noData = row(recording, 500)
        assertEquals(SignalAvailability.UNAVAILABLE, noData.availability)
        assertNull(noData.value); assertNull(noData.observedAt); assertNull(noData.ageMillis)
        assertContains(noData.reason, "ECU returned NO DATA")
        assertEquals(SignalValue.Number(700.0), row(recording, 1000).value)
        assertEquals(5L, row(recording, 1000).ageMillis)
        val expired = row(recording, 1500)
        assertEquals(SignalAvailability.STALE, expired.availability)
        assertNull(expired.value)
        assertEquals(5000L, expired.ageMillis)
        assertContains(expired.reason, "Observation expired")
    }

    @Test fun exporterOutputWithAnAvailableValueLackingTimeOrAgeIsRejectedByTheConsumer() {
        // The Python validator also rejects these; the Kotlin consumer must not depend on that.
        for (bad in listOf(event(0, "rpm", "700", first, null), event(0, "rpm", "700", null, 0), event(0, "rpm", "700", null, null))) {
            assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(rpmOnly(bad, event(100, "rpm", "701", second, 0))), bad)
            assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(rpmOnly(event(0, "rpm", "700", first, 0), bad.replace("\"at_ms\":0", "\"at_ms\":100"))), bad)
        }
    }
}
