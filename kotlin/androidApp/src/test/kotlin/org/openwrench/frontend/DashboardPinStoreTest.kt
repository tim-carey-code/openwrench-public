package org.openwrench.frontend

import kotlin.test.*

class DashboardPinStoreTest {
    @Test fun orderAndEmptyPinsSurviveRestartWithSourcesSeparatedAndNoAcquisitionState() {
        val preferences = mutableMapOf<String, String>()
        fun store() = StoredDashboardPinStore(preferences::get) { key, value -> preferences[key] = value; true }
        val first = store()
        assertNull(first.load(DashboardSource.HARDWARE))
        first.save(DashboardSource.HARDWARE, listOf("speed", "rpm", "coolant"))
        first.save(DashboardSource.DEMO, emptyList())
        val next = store()
        assertEquals(listOf("speed", "rpm", "coolant"), next.load(DashboardSource.HARDWARE))
        assertEquals(emptyList(), next.load(DashboardSource.DEMO))
        assertNull(next.load(DashboardSource.CIVIC_REPLAY))
        val stored = preferences.toString()
        assertFalse(stored.contains("session", true)); assertFalse(stored.contains("lease", true))
        assertFailsWith<IllegalArgumentException> { next.save(DashboardSource.HARDWARE, listOf("rpm", "rpm")) }
        assertEquals(listOf("speed", "rpm", "coolant"), next.load(DashboardSource.HARDWARE))
    }

    @Test fun corruptedStorageAndFailedWritesAreVisibleToTheDashboard() {
        val invalid = StoredDashboardPinStore({ "rpm,rpm" }) { _, _ -> true }
        assertFailsWith<IllegalArgumentException> { invalid.load(DashboardSource.HARDWARE) }
        val failed = StoredDashboardPinStore({ null }) { _, _ -> false }
        assertFailsWith<IllegalStateException> { failed.save(DashboardSource.HARDWARE, listOf("rpm")) }
    }

    @Test fun consecutiveSnapshotImportsRefreshOnlyAutomaticDefaults() {
        val firstCatalog = listOf("rpm", "coolant")
        val firstPins = snapshotPinsForCatalog(firstCatalog, emptyList(), savedPins = null, edited = false)
        assertEquals(firstCatalog, firstPins)
        val nextCatalog = listOf("runtime", "speed", "load", "maf", "throttle")
        assertEquals(nextCatalog.take(4), snapshotPinsForCatalog(nextCatalog, firstPins, savedPins = null, edited = false))
        assertEquals(firstPins, snapshotPinsForCatalog(nextCatalog, firstPins, savedPins = firstPins, edited = false))
        assertEquals(listOf("coolant"), snapshotPinsForCatalog(nextCatalog, listOf("coolant"), savedPins = null, edited = true))
        // An intentionally empty layout is also a preference, not a request for new defaults.
        assertEquals(emptyList(), snapshotPinsForCatalog(nextCatalog, emptyList(), savedPins = emptyList(), edited = false))
        assertEquals(emptyList(), snapshotPinsForCatalog(nextCatalog, emptyList(), savedPins = null, edited = true))
    }
}
