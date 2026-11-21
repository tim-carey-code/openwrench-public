package org.openwrench.frontend.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosisAiRequestsTest {
    @Test fun hidingAndReopeningObserveOneRequestUntilItsResultArrives() = runTest {
        val requests = DiagnosisAiRequests(backgroundScope)
        val response = CompletableDeferred<String>()
        val previewRevision = requests.revision
        var posts = 0
        assertTrue(requests.submit("first", previewRevision) { posts++; response.await() })
        val oldDialog = launch { requests.pending.first { it == null } }
        runCurrent()
        oldDialog.cancel()
        assertFalse(requests.submit("first", previewRevision) { error("duplicate post") })
        assertFalse(requests.submit("second", requests.revision) { error("overlapping post") })
        val reopened = launch {
            requests.pending.first { it == null }
            assertEquals("assessment", requests.result("first")?.getOrThrow())
        }
        response.complete("assessment")
        reopened.join()
        assertEquals(1, posts)
        assertNull(requests.result("second"))
        assertFalse(requests.submit("first", previewRevision) { error("stale preview") })
        assertTrue(requests.submit("second", requests.revision) { error("timeout") })
        runCurrent()
        assertNull(requests.pending.value)
        assertEquals("timeout", requests.result("second")?.exceptionOrNull()?.message)
    }
    @Test fun noticeSurvivesHiddenDialogAndDismissalKeepsResult() = runTest {
        val requests = DiagnosisAiRequests(backgroundScope)
        val response = CompletableDeferred<String>()
        assertTrue(requests.submit("selected", requests.revision) { response.await() })
        val running = requests.notice.value!!
        assertEquals(AiAnalysisNotice("selected", AiAnalysisPhase.RUNNING), running)
        requests.dismissNotice(running)
        assertEquals(running, requests.notice.value)

        response.complete("assessment")
        runCurrent()
        val ready = requests.notice.value!!
        assertEquals(AiAnalysisNotice("selected", AiAnalysisPhase.READY), ready)
        requests.dismissNotice(ready)
        assertNull(requests.notice.value)
        assertEquals("assessment", requests.result("selected")?.getOrThrow())

        assertTrue(requests.submit("another", requests.revision) { error("timeout") })
        runCurrent()
        assertEquals(AiAnalysisNotice("another", AiAnalysisPhase.FAILED), requests.notice.value)
        requests.dismissNotice(ready)
        assertEquals(AiAnalysisNotice("another", AiAnalysisPhase.FAILED), requests.notice.value)
    }
}
