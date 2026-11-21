package org.openwrench.frontend

import kotlin.test.*
import org.junit.Test
import org.openwrench.frontend.android.DiagnosisPhase

class GuiComponentsTest {
    @Test fun cardColumnsRespectReadableWidthAtTextScales() {
        for (scale in listOf(1f, 1.3f, 1.5f, 2f, 3f)) {
            for (width in listOf(240f, 320f, 366f, 600f, 900f, 1400f)) {
                val columns = guiValueColumns(width, scale)
                assertTrue(columns in 1..4)
                if (columns > 1) assertTrue((width - 12 * (columns - 1)) / columns >= 172 * scale)
            }
        }
        assertEquals(1, guiValueColumns(Float.NaN, 1f))
        assertEquals(1, guiValueColumns(0f, 1f))
        assertEquals(2, guiValueColumns(366f, Float.NaN))
    }

    @Test fun interruptedAndPausedCapturesNeverAdvanceToCompletedReview() {
        for (phase in listOf(DiagnosisPhase.PAUSED, DiagnosisPhase.PAUSING, DiagnosisPhase.INTERRUPTED)) {
            assertEquals(2, diagnosisJourneyIndex(phase, false))
            assertEquals(2, diagnosisJourneyIndex(phase, true))
        }
        assertEquals(0, diagnosisJourneyIndex(DiagnosisPhase.IDLE, false))
        assertEquals(1, diagnosisJourneyIndex(DiagnosisPhase.IDLE, true))
        assertEquals(1, diagnosisJourneyIndex(DiagnosisPhase.STARTING, false))
        assertEquals(3, diagnosisJourneyIndex(DiagnosisPhase.FINISHING, false))
        assertEquals(4, diagnosisJourneyIndex(DiagnosisPhase.COMPLETED, false))
    }
}
