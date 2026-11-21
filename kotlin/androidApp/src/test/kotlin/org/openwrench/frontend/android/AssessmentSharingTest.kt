package org.openwrench.frontend.android

import kotlin.test.Test
import kotlin.test.assertEquals

class AssessmentSharingTest {
    @Test
    fun exportAddsWarningWithoutChangingWriteup() {
        val writeup = "## Observations\n\n" + "- **Recorded note:** 15 °C\n".repeat(400) + "\nEnd of assessment.\n"

        assertEquals(
            "# AI assessment — not a confirmed diagnosis\n\n$writeup",
            assessmentExportText(writeup),
        )
    }
}
