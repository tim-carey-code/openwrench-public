package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*

class DashboardVisualStateTest {
    private val rpm = uncheckedRpm.copy(support = SignalSupport.SUPPORTED)

    @Test fun gaugeNeverTurnsMissingStaleDisconnectedOrUnsupportedIntoZero() {
        for (state in SignalAvailability.entries.filter { it != SignalAvailability.AVAILABLE }) {
            assertFailsWith<IllegalArgumentException> { DashboardSignal(rpm, state, SignalValue.Number(715.0)) }
            val row = DashboardSignal(rpm, state)
            assertNull(row.gaugeFraction(), state.name)
            assertEquals("—", row.visibleValue())
        }
        for (support in listOf(SignalSupport.UNKNOWN, SignalSupport.UNSUPPORTED)) {
            assertFailsWith<IllegalArgumentException> {
                DashboardSignal(rpm.copy(support = support), SignalAvailability.AVAILABLE, SignalValue.Number(715.0))
            }
            val row = DashboardSignal(rpm.copy(support = support))
            assertNull(row.gaugeFraction())
            assertEquals("—", row.visibleValue())
        }
        val zero = DashboardSignal(rpm, SignalAvailability.AVAILABLE, SignalValue.Number(0.0))
        assertEquals(0f, zero.gaugeFraction())
        assertEquals("0", zero.visibleValue())
    }

    @Test fun displayScaleClampsOnlyTheArcAndPreservesTheActualReading() {
        val high = DashboardSignal(rpm, SignalAvailability.AVAILABLE, SignalValue.Number(12000.0))
        assertEquals(1f, high.gaugeFraction())
        assertEquals("12000", high.visibleValue())
        val load = SignalDefinition("load", "Load", "%", SignalType.NUMBER, SignalSupport.SUPPORTED, "0104")
        assertEquals(.5f, DashboardSignal(load, SignalAvailability.AVAILABLE, SignalValue.Number(50.0)).gaugeFraction())
        val negative = DashboardSignal(load, SignalAvailability.AVAILABLE, SignalValue.Number(-2.0))
        assertEquals(0f, negative.gaugeFraction())
        assertEquals("-2", negative.visibleValue())
        val temperature = load.copy(id = "coolant", unit = "°C", pid = "0105")
        assertNull(DashboardSignal(temperature, SignalAvailability.AVAILABLE, SignalValue.Number(85.0)).gaugeFraction(),
            "No unverified temperature range is invented")
    }

    @Test fun layoutRespondsToWidthAndAccessibilityFontScale() {
        assertEquals(DashboardLayout.PHONE, dashboardLayout(390f, 1f))
        assertEquals(DashboardLayout.PHONE, dashboardLayout(639f, 1f))
        assertEquals(DashboardLayout.TABLET, dashboardLayout(640f, 1f))
        assertEquals(DashboardLayout.TABLET, dashboardLayout(999f, 1f))
        assertEquals(DashboardLayout.DESKTOP, dashboardLayout(1000f, 1f))
        assertEquals(DashboardLayout.PHONE, dashboardLayout(1000f, 2f))
        assertEquals(DashboardLayout.TABLET, dashboardLayout(1400f, 2f))
    }

    @Test fun compactDockKeepsAllFourIconTargetsWithoutGrowingWithText() {
        // The dock receives width after 8dp side padding, not measured text width.
        for (width in listOf(280f, 320f, 390f, 600f)) {
            assertEquals(4, phoneNavigationColumns(width - 16f))
            assertTrue((width - 16f) / 4 >= 48f)
        }
        assertEquals(4, phoneNavigationColumns(192f))
        assertEquals(2, phoneNavigationColumns(191f))
        assertEquals(2, phoneNavigationColumns(96f))
        assertEquals(1, phoneNavigationColumns(95f))
        for (width in listOf(48f, 95f, 96f, 191f, 192f, 374f)) {
            val columns = phoneNavigationColumns(width)
            assertEquals(0, 4 % columns, "No destination is dropped in a partial row")
            assertTrue(width / columns >= 48f)
        }
    }

    @Test fun ordinaryCompactWorkspaceAlwaysHasDestinationNavigation() {
        assertEquals(AppNavigationPlacement.PHONE_DOCK, appNavigationPlacement(390f, 1f, false))
        assertEquals(AppNavigationPlacement.PHONE_DOCK, appNavigationPlacement(699f, 1f, false))
        assertEquals(AppNavigationPlacement.SIDEBAR, appNavigationPlacement(700f, 1f, false))
        assertEquals(AppNavigationPlacement.SIDEBAR, appNavigationPlacement(1280f, 1f, false))
        assertEquals(AppNavigationPlacement.PHONE_DOCK, appNavigationPlacement(1280f, 2f, false))
        assertEquals(AppNavigationPlacement.SIDEBAR, appNavigationPlacement(1400f, 2f, false))
    }

    @Test fun fullscreenHidesDockAndExitRestoresItForEveryDisplayMode() {
        for (mode in LiveDisplayMode.entries) {
            val display = LiveDisplayState().apply { this.mode = mode }
            assertEquals(AppNavigationPlacement.PHONE_DOCK, appNavigationPlacement(390f, 2f, display.fullScreen))
            display.enterFullScreen()
            assertEquals(AppNavigationPlacement.HIDDEN, appNavigationPlacement(390f, 2f, display.fullScreen))
            assertEquals(AppNavigationPlacement.HIDDEN, appNavigationPlacement(1280f, 1f, display.fullScreen))
            display.exitFullScreen()
            assertEquals(mode, display.mode)
            assertEquals(AppNavigationPlacement.PHONE_DOCK, appNavigationPlacement(390f, 2f, display.fullScreen))
        }
    }

    @Test fun ageTextKeepsUnknownAgeSeparateFromMeasuredZero() {
        assertEquals("Observation age unknown", DashboardSignal(rpm).ageCaption())
        assertEquals("Observation age: 0.0 s", DashboardSignal(rpm, ageMillis = 0).ageCaption())
        assertEquals("Age on recording timeline: 5.0 s", DashboardSignal(rpm,
            ageMillis = 5000, ageLabel = "Age on recording timeline").ageCaption())
    }
}
