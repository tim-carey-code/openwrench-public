package org.openwrench.frontend.android

import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.test.*

/** Synthetic policy checks; no exception was captured on a physical tablet. */
class ImportPickerLaunchTest {
    private class Harness {
        val attempts = mutableListOf<ImportPickerRoute>()
        val failures = mutableListOf<Pair<ImportPickerRoute, RuntimeException>>()
        var documentFailure: RuntimeException? = null
        var contentFailure: RuntimeException? = null
        fun launch() = launchImportPicker({ route ->
            attempts += route
            val failure = if (route == ImportPickerRoute.DOCUMENT) documentFailure else contentFailure
            failure?.let { throw it }
        }, { route, failure -> failures += route to failure })
    }

    @Test fun primarySuccessDoesNotLaunchAnotherPicker() {
        val h = Harness()
        assertTrue(h.launch())
        assertEquals(listOf(ImportPickerRoute.DOCUMENT), h.attempts)
        assertTrue(h.failures.isEmpty())
    }

    @Test fun unavailablePrimaryFallsBackExactlyOnce() {
        val h = Harness().apply { documentFailure = RuntimeException("synthetic missing provider") }
        assertTrue(h.launch())
        assertEquals(ImportPickerRoute.entries.toList(), h.attempts)
        assertSame(h.documentFailure, h.failures.single().second)
    }

    @Test fun blockedPrimaryCanUseAlternatePicker() {
        val h = Harness().apply { documentFailure = SecurityException() }
        assertTrue(h.launch())
        assertEquals(ImportPickerRoute.entries.toList(), h.attempts)
    }

    @Test fun bothUnavailableBlockedOrInvalidStateAreRecoverable() {
        for (primary in listOf(RuntimeException(), SecurityException(), IllegalStateException())) {
            for (fallback in listOf(RuntimeException(), SecurityException(), IllegalStateException())) {
                val h = Harness().apply { documentFailure = primary; contentFailure = fallback }
                assertFalse(h.launch())
                assertEquals(ImportPickerRoute.entries.toList(), h.attempts)
                assertEquals(listOf(ImportPickerRoute.DOCUMENT to primary, ImportPickerRoute.CONTENT to fallback), h.failures)
            }
        }
    }

    @Test fun repeatedFailureLeavesNoPendingStateAndRetryStartsWithPrimary() {
        val h = Harness().apply { documentFailure = SecurityException(); contentFailure = IllegalStateException() }
        repeat(2) { assertFalse(h.launch()) }
        h.documentFailure = null
        assertTrue(h.launch())
        assertEquals(listOf(ImportPickerRoute.DOCUMENT, ImportPickerRoute.CONTENT,
            ImportPickerRoute.DOCUMENT, ImportPickerRoute.CONTENT, ImportPickerRoute.DOCUMENT), h.attempts)
    }

    @Test fun fallbackSuccessDoesNotPermanentlyBypassPrimary() {
        val h = Harness().apply { documentFailure = RuntimeException() }
        assertTrue(h.launch())
        h.documentFailure = null
        assertTrue(h.launch())
        assertEquals(listOf(ImportPickerRoute.DOCUMENT, ImportPickerRoute.CONTENT, ImportPickerRoute.DOCUMENT), h.attempts)
    }

    @Test fun canceledOrMissingResultRemainsNullWithoutAnotherLaunch() {
        for (fallback in listOf(false, true)) {
            val h = Harness()
            if (fallback) h.documentFailure = RuntimeException()
            val contract = if (fallback) OpenableContent() else ActivityResultContracts.OpenDocument()
            assertTrue(h.launch())
            val attempts = h.attempts.toList()
            assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
            assertNull(contract.parseResult(Activity.RESULT_OK, null))
            assertEquals(attempts, h.attempts)
            assertTrue(h.launch())
            assertEquals(attempts + attempts, h.attempts)
        }
    }

    @Test fun fatalErrorsAreNotDisguisedAsUnavailableProviders() {
        assertFailsWith<AssertionError> {
            launchImportPicker({ throw AssertionError("synthetic fatal failure") }, { _, _ -> fail() })
        }
    }
}
