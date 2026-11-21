package org.openwrench.frontend.android

import org.openwrench.nativecore.*
import java.nio.file.Files
import kotlin.test.*

class WarningContextReportTest {
    private val context = DiagnosticContextRead(listOf(
        DiagnosticCodeRead(DtcService.STORED, DiagnosticReplyStatus.AVAILABLE, emptyList(), "7E8"),
        DiagnosticCodeRead(DtcService.PENDING, DiagnosticReplyStatus.AVAILABLE, listOf("P0133"), "7E8"),
        DiagnosticCodeRead(DtcService.PERMANENT, DiagnosticReplyStatus.NO_DATA, sourceEcu = "7E8")),
        FreezeFrameRead(DiagnosticReplyStatus.AVAILABLE, "P0133", listOf(NativeReading(0x0C, DecodedReading.Scalar(2_000.0), "7E8")), "7E8"))
    @Test fun reportDistinguishesCodeStatesFreezeFrameAndHistoricalSessionAndCanBeSaved() {
        val state = NativeUiState(connected = true, sessionGeneration = 5, snapshotSessionGeneration = 5,
            snapshot = listOf(NativeReading(0x0C, DecodedReading.Scalar(800.0))), diagnosticContext = context)
        val report = DiagnosticScanDelivery.present(state).reportText()
        assertTrue(report.contains("Pending codes (07); source 7E8: P0133"))
        assertTrue(report.contains("Permanent codes (0A); source 7E8: NO DATA; status unknown"))
        assertTrue(report.contains("unknown earlier time"))
        assertTrue(report.contains("not live samples"))
        assertTrue(report.contains("do not alone establish a current fault"))
        val historical = DiagnosticScanDelivery.present(state.copy(connected = false, sessionGeneration = 6))
        assertEquals(DiagnosticScanStatus.HISTORICAL, historical.status)
        assertEquals(context, historical.warningContext)
        assertNull(DiagnosticScanDelivery.present(state.copy(snapshotInProgress = true)).warningContext)
        assertNull(DiagnosticScanDelivery.present(state.copy(origin = ReadingOrigin.REPLAY)).warningContext)
        val dir = Files.createTempDirectory("ow-warning-report").toFile()
        try {
            val entry = ScanHistoryCodec.newEntry(report, null)
            ScanHistoryStore(dir).add(entry)
            assertEquals(report, ScanHistoryStore(dir).load().single().report)
        } finally { dir.deleteRecursively() }
    }
}
