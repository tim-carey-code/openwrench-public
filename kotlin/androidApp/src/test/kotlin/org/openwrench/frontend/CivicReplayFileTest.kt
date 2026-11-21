package org.openwrench.frontend

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.openwrench.core.display
import kotlin.test.*

/** Inline SYNTHETIC schema examples, never a shipped or real Civic recording. */
class CivicReplayFileTest {
    private fun example() = """{
      "schema":"openwrench.civic-replay/1",
      "capture":{"origin":"hardware","vehicle_label":"Honda Civic (synthetic test)",
        "captured_at_utc":"2020-01-01T00:00:00Z","clock_basis":"backend_observation",
        "provenance":"SYNTHETIC UNIT TEST ONLY; not a real recording"},
      "duration_ms":1000,
      "signals":[{"id":"rpm","pid":"010C","label":"RPM","unit":"rpm","value_type":"number","support":"supported"}],
      "observations":[
        {"at_ms":0,"signal":"rpm","availability":"available","value":700.0,"observed_at_utc":"2020-01-01T00:00:00Z","age_at_capture_ms":0,"reason":null},
        {"at_ms":400,"signal":"rpm","availability":"unavailable","value":null,"observed_at_utc":null,"age_at_capture_ms":null,"reason":"NO DATA"}
      ]} """

    @Test fun boundedDocumentMapsOriginalFactsIntoTheExistingDashboardRows() {
        val capture = assertIs<ReplayLoadResult.Loaded>(readCivicRecording(example().byteInputStream())).recording
        assertEquals(listOf(0L, 400L), capture.observations.map { it.atMillis })
        assertEquals("2020-01-01T00:00:00Z", capture.capture.capturedAtUtc)
        assertEquals("backend_observation", capture.capture.clockBasis)
        assertEquals("NO DATA", capture.observations.last().reason)
        assertTrue(capture.observations.last().value == null)
    }

    @Test fun receiptCapturePreservesSourceAgeAndObservedNoDataTransition() {
        val json = example().replace("backend_observation", "client_receipt")
            .replace("\"duration_ms\":1000", "\"duration_ms\":10000")
            .replace("\"at_ms\":0", "\"at_ms\":1000")
            .replace("\"at_ms\":400", "\"at_ms\":6000")
            .replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":200")
        val capture = assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(json)).recording
        fun row(at: Long) = CivicReplayState(recording = capture, positionMillis = at,
            observations = capture.observations.takeWhile { it.atMillis <= at }).dashboardSignals().single()
        assertEquals(200L, row(1000).ageMillis)
        assertNotNull(row(5799).value)
        assertNull(row(5800).value)
        assertEquals(SignalAvailability.STALE, row(5800).availability)
        assertEquals(SignalAvailability.UNAVAILABLE, row(6000).availability)
        assertNull(row(6000).ageMillis)
        assertNull(row(6000).observedAt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun documentFeedsPausedPlayerUnavailableTransitionAndLoopReset() = runTest {
        val player = CivicReplayPlayer(backgroundScope, object : FrontendClock {
            override fun elapsedMillis() = testScheduler.currentTime
            override fun utc() = "NOT AN ORIGINAL CAPTURE TIME"
        })
        try {
            player.load(readCivicRecording(example().byteInputStream()))
            assertEquals(ReplayPhase.PAUSED, player.state.value.phase)
            assertEquals("700", player.state.value.dashboardSignals().single().value?.display())
            player.play(); runCurrent(); advanceTimeBy(400); runCurrent()
            val unavailable = player.state.value.dashboardSignals().single()
            assertNull(unavailable.value); assertNull(unavailable.ageMillis)
            assertEquals(SignalAvailability.UNAVAILABLE, unavailable.availability)
            assertContains(unavailable.reason, "NO DATA")
            advanceTimeBy(600); runCurrent()
            assertEquals(1L, player.state.value.loop)
            assertEquals(1, player.state.value.observations.size)
            assertEquals("2020-01-01T00:00:00Z", player.state.value.dashboardSignals().single().observedAt)
        } finally { player.close() }
    }

    @Test fun wrongSourceVersionUnitsTypesTimingAndUnknownFieldsFailClosed() {
        for (invalid in listOf(
            example().replace("\"hardware\"", "\"simulator\""),
            example().replace("civic-replay/1", "civic-replay/2"),
            example().replace("\"rpm\",\"value_type\"", "\"km/h\",\"value_type\""),
            example().replace("\"010C\"", "\"010D\""),
            example().replace("\"supported\"", "\"unsupported\""),
            example().replace("\"at_ms\":400", "\"at_ms\":0"),
            example().replace("\"at_ms\":400", "\"at_ms\":-1"),
            example().replace("\"duration_ms\":1000", "\"duration_ms\":400"),
            example().replace("\"value\":700.0", "\"value\":\"700.0\""),
            example().replace("\"value\":700.0", "\"value\":null"),
            example().replace("\"value\":null", "\"value\":0"),
            example().replace("\"clock_basis\":\"backend_observation\"", "\"clock_basis\":\"guessed\""),
            example().replace("2020-01-01T00:00:00Z", "not-a-timestamp"),
            example().replace("\"duration_ms\":1000", "\"private_vin\":\"not-allowed\",\"duration_ms\":1000"),
        )) assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(invalid))
    }

    @Test fun missingOrUnknownAgesAreRejectedForAvailableValuesAndStayUnknownOtherwise() {
        val available = "\"observed_at_utc\":\"2020-01-01T00:00:00Z\",\"age_at_capture_ms\":0"
        assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(example()))
        for (invalid in listOf(
            example().replace(",\"age_at_capture_ms\":0", ""),
            example().replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":null"),
            example().replace(available, "\"observed_at_utc\":null,\"age_at_capture_ms\":0"),
            example().replace(available, "\"observed_at_utc\":null,\"age_at_capture_ms\":null"),
            example().replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":-1"),
            example().replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":\"0\""),
            example().replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":0.5"),
            example().replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":9223372036854775808"),
            example().replace("\"age_at_capture_ms\":null", "\"age_at_capture_ms\":-1"),
            example().replace(",\"age_at_capture_ms\":null", ""),
        )) assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(invalid), invalid)
        val unknown = assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(example())).recording.observations.last()
        assertNull(unknown.ageAtCaptureMillis)
    }

    @Test fun maximumSourceAgeLoadsAsExpiredWithoutOverflow() {
        val capture = assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(
            example().replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":9223372036854775807"))).recording
        val row = CivicReplayState(recording = capture, positionMillis = 400,
            observations = capture.observations.takeWhile { it.atMillis < 400 }).dashboardSignals().single()
        assertEquals(SignalAvailability.STALE, row.availability)
        assertEquals(Long.MAX_VALUE, row.ageMillis)
        assertNull(row.value)
    }

    @Test fun malformedMetadataFailsClosed() {
        val capture = "\"capture\":{\"origin\":\"hardware\",\"vehicle_label\":\"Honda Civic (synthetic test)\","
        for (invalid in listOf(
            example().replace("Honda Civic (synthetic test)", "Toyota Camry"),
            example().replace("Honda Civic (synthetic test)", "Honda Accord"),
            example().replace("Honda Civic (synthetic test)", "Civic"),
            example().replace("Honda Civic (synthetic test)", "Honda Civic " + "x".repeat(100)),
            example().replace("Honda Civic (synthetic test)", ""),
            example().replace("\"vehicle_label\":\"Honda Civic (synthetic test)\"", "\"vehicle_label\":7"),
            example().replace("\"vehicle_label\":\"Honda Civic (synthetic test)\"", "\"vehicle_label\":null"),
            example().replace("\"clock_basis\":\"backend_observation\"", "\"clock_basis\":null"),
            example().replace("\"clock_basis\":\"backend_observation\"", "\"clock_basis\":\"Client_Receipt\""),
            example().replace("SYNTHETIC UNIT TEST ONLY; not a real recording", "   "),
            example().replace("SYNTHETIC UNIT TEST ONLY; not a real recording", "p".repeat(1001)),
            example().replace("\"provenance\":\"SYNTHETIC UNIT TEST ONLY; not a real recording\"", "\"provenance\":null"),
            example().replaceFirst("\"captured_at_utc\":\"2020-01-01T00:00:00Z\"", "\"captured_at_utc\":\"2020-01-01T00:00:00+00:00\""),
            example().replaceFirst("\"captured_at_utc\":\"2020-01-01T00:00:00Z\"", "\"captured_at_utc\":\"2020-01-01T00:00:00z\""),
            example().replaceFirst("\"captured_at_utc\":\"2020-01-01T00:00:00Z\"", "\"captured_at_utc\":\"2020-01-01\""),
            example().replaceFirst("\"captured_at_utc\":\"2020-01-01T00:00:00Z\"", "\"captured_at_utc\":20200101"),
            example().replace("\"captured_at_utc\":\"2020-01-01T00:00:00Z\",", ""),
            example().replace("\"origin\":\"hardware\",", ""),
            example().replace(capture, "\"capture\":{\"origin\":\"hardware\",\"vehicle_label\":\"Honda Civic\",\"device\":\"AA:BB\","),
            example().replace(Regex("\"capture\":\\{[^}]*\\}"), "\"capture\":[]"),
            example().replace(Regex("\"capture\":\\{[^}]*\\}"), "\"capture\":null"),
            example().replace("\"origin\":\"hardware\"", "\"origin\":\"Hardware\""),
            example().replace("\"origin\":\"hardware\"", "\"origin\":true"),
        )) assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(invalid), invalid)
        // A recording that did not record its start time is valid; the label limit is inclusive.
        assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(
            example().replaceFirst("\"captured_at_utc\":\"2020-01-01T00:00:00Z\"", "\"captured_at_utc\":null")))
        assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(
            example().replace("Honda Civic (synthetic test)", "Honda Civic " + "x".repeat(88))))
        assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(
            example().replace("SYNTHETIC UNIT TEST ONLY; not a real recording", "p".repeat(1000))))
    }

    @Test fun malformedDocumentsAndNumbersFailClosed() {
        for (invalid in listOf("", "   ", "[]", "null", "{", "{}", example() + "}", "\u0000" + example(),
            example().replace("\"duration_ms\":1000", "\"duration_ms\":\"1000\""),
            example().replace("\"duration_ms\":1000", "\"duration_ms\":1000.5"),
            example().replace("\"duration_ms\":1000", "\"duration_ms\":0"),
            example().replace("\"duration_ms\":1000", "\"duration_ms\":300001"),
            example().replace("\"duration_ms\":1000", "\"duration_ms\":99999999999999999999"),
            example().replace("\"at_ms\":400", "\"at_ms\":400.0"),
            example().replace("\"at_ms\":400", "\"at_ms\":\"400\""),
            example().replace("\"value\":700.0", "\"value\":1e999"),
            example().replace("\"value\":700.0", "\"value\":NaN"),
            example().replace("\"value\":700.0", "\"value\":Infinity"),
            example().replace("\"value\":700.0", "\"value\":true"),
            example().replace("\"value\":700.0", "\"value\":[700]"),
            example().replace("\"availability\":\"available\"", "\"availability\":\"AVAILABLE\""),
            example().replace("\"availability\":\"available\"", "\"availability\":\"not_sampled\""),
            example().replace("\"reason\":\"NO DATA\"", "\"reason\":\"" + "r".repeat(501) + "\""),
            example().replace("\"reason\":\"NO DATA\"", "\"reason\":7"),
            example().replace("\"signal\":\"rpm\",\"availability\":\"unavailable\"", "\"signal\":\"unknown\",\"availability\":\"unavailable\""),
            example().replace("\"signals\":[", "\"signals\":[null,"),
            example().replace("\"observations\":[", "\"observations\":[[],"),
            example().replace(Regex("\"signals\":\\[.*?\\}\\],"), "\"signals\":[],"),
        )) assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(invalid), invalid.take(120))
    }

    @Test fun duplicateAndExtraSignalsAndMetadataFieldsAreRejected() {
        val rpm = "{\"id\":\"rpm\",\"pid\":\"010C\",\"label\":\"RPM\",\"unit\":\"rpm\",\"value_type\":\"number\",\"support\":\"supported\"}"
        assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(example().replace("\"signals\":[$rpm", "\"signals\":[$rpm,$rpm")))
        assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(example().replace("\"label\":\"RPM\"", "\"label\":\"\"")))
        assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(example().replace("\"label\":\"RPM\"", "\"label\":\"" + "L".repeat(101) + "\"")))
        assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(example().replace("\"reason\":null}", "\"reason\":null,\"vin\":\"x\"}")))
        assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(example().replace("\"support\":\"supported\"}", "\"support\":\"supported\",\"extra\":1}")))
    }

    @Test fun duplicateObjectKeysAtEveryReplayLevelFailClosed() {
        val valid = example()
        val malformed = listOf(
            valid.replace("\"duration_ms\":1000", "\"duration_ms\":2000,\"duration_ms\":1000"),
            valid.replace("\"origin\":\"hardware\"", "\"origin\":\"simulator\",\"origin\":\"hardware\""),
            valid.replace("\"pid\":\"010C\"", "\"pid\":\"01FF\",\"pid\":\"010C\""),
            valid.replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":null,\"age_at_capture_ms\":0"),
            valid.replace("\"observed_at_utc\":\"2020-01-01T00:00:00Z\"", "\"observed_at_utc\":null,\"observed_at_utc\":\"2020-01-01T00:00:00Z\""),
            valid.replace("\"age_at_capture_ms\":null", "\"age_at_capture_ms\":0,\"age_at_capture_ms\":null"),
            valid.replace("\"age_at_capture_ms\":0", "\"age_at_capture_ms\":0,\"age_at_capture_\\u006ds\":0"),
        )
        assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(valid))
        malformed.forEach { assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(it), it) }
    }

    @Test fun badUtf8OversizedAndUnreadableDocumentsAreTypedErrors() {
        assertIs<ReplayLoadResult.Invalid>(readCivicRecording(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))))
        assertIs<ReplayLoadResult.Invalid>(readCivicRecording(ByteArrayInputStream(ByteArray(262_145) { 32 })))
        assertIs<ReplayLoadResult.Invalid>(readCivicRecording(object : InputStream() {
            override fun read(): Int = throw IOException("Document provider unavailable")
        }))
    }

    @Test fun documentBoundIsUtf8BytesAndReadStopsAtTheLimit() {
        val exact = example().padEnd(262_144, ' ')
        assertIs<ReplayLoadResult.Loaded>(readCivicRecording(exact.byteInputStream()))
        assertIs<ReplayLoadResult.Loaded>(readCivicRecordingText(exact))
        val multiByte = example().replace("Honda Civic (synthetic test)", "Honda Civic °").padEnd(262_144, ' ')
        assertEquals(262_144, multiByte.length)
        assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(multiByte))
        assertIs<ReplayLoadResult.Invalid>(readCivicRecordingText(example().replace("NO DATA", "\uD800")))
        var bytesRead = 0
        val endless = object : InputStream() {
            override fun read(): Int { bytesRead++; return 32 }
        }
        assertIs<ReplayLoadResult.Invalid>(readCivicRecording(endless))
        assertEquals(262_145, bytesRead)
    }

    @Test fun timestampsMatchTheReferenceInstantValidationWithoutAndroidApi26() {
        val timestamps = listOf(
            "2020-01-01T00:00:00Z", "2020-02-29T12:30:59.123456789Z", "1900-02-29T00:00:00Z",
            "2000-02-29T00:00:00Z", "2020-04-31T00:00:00Z", "2020-00-01T00:00:00Z",
            "2020-13-01T00:00:00Z", "2020-01-00T00:00:00Z", "2020-01-01T24:00:00Z",
            "2020-01-01T24:00:00.000Z", "2020-01-01T24:00:00.001Z", "2020-01-01T24:01:00Z",
            "2020-01-01T23:59:60Z", "2020-01-01T12:59:60Z", "2020-01-01T12:60:00Z",
            "2020-01-01T00:00:00.Z", "2020-01-01T00:00:00.1234567890Z", "2020-01-01t00:00:00Z",
            "2020-01-01T00:00:00z", "2020-01-01T00:00:00+00:00", "0000-02-29T00:00:00Z",
            "-0000-01-01T00:00:00Z", "+2020-01-01T00:00:00Z", "+02020-01-01T00:00:00Z",
            "-02020-01-01T00:00:00Z", "+1000000000-12-31T23:59:59.999999999Z",
            "+1000000000-12-31T24:00:00Z", "-1000000000-01-01T00:00:00Z",
            "+1000000001-01-01T00:00:00Z", "-1000000001-12-31T23:59:59Z",
        )
        for (timestamp in timestamps) {
            val referenceAccepts = timestamp.endsWith("Z") && runCatching { Instant.parse(timestamp) }.isSuccess
            val result = readCivicRecordingText(example().replace("2020-01-01T00:00:00Z", timestamp))
            assertEquals(referenceAccepts, result is ReplayLoadResult.Loaded, timestamp)
            if (result is ReplayLoadResult.Loaded) {
                assertEquals(timestamp, result.recording.capture.capturedAtUtc)
                assertEquals(timestamp, result.recording.observations.first().observedAtUtc)
            }
        }
    }
}
