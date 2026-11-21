package org.openwrench.frontend.android.transport

import org.openwrench.nativecore.NativeDiagnosticException
import kotlin.test.*

class PendingReleasesTest {
    @Test fun nonRetryableWrapperCannotConfirmCleanupWithANoopSecondClose() {
        var closes = 0
        val releases = PendingReleases<String>(retryable = false) {
            closes++
            if (closes == 1) error("Wrapper marked closed before native cleanup failed")
        }
        releases.attempt("socket")
        assertFailsWith<NativeDiagnosticException> { releases.retry() }
        releases.attempt("socket")
        assertFailsWith<NativeDiagnosticException> { releases.requireReleased() }
        assertEquals(1, closes)
    }

    @Test fun failedCloseRetainsOwnershipUntilRetrySucceeds() {
        var fail = true
        val attempted = mutableListOf<String>()
        val releases = PendingReleases<String> { attempted.add(it); if (fail) error("close failed") }
        releases.attempt("old socket")
        assertFailsWith<NativeDiagnosticException> { releases.requireReleased() }
        assertFailsWith<NativeDiagnosticException> { releases.retry() }
        fail = false
        releases.retry()
        releases.requireReleased()
        assertEquals(listOf("old socket", "old socket", "old socket"), attempted)
    }
}
