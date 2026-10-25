package com.sbro.emucoreh.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanRequestsTest {
    @Test fun `superseded scan cannot publish even when blocking provider finishes after replacement`() = runBlocking {
        val requests = LibraryScanRequests(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publications = mutableListOf<String>()
        var oldStillCurrent = true
        val old = requests.launch { request ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                oldStillCurrent = request.isCurrent
                request.ensureCurrent()
                publications += "old"
            }
        }
        entered.await()
        requests.launch { publications += "new" }.join()
        release.complete(Unit)
        old.join()
        assertFalse(oldStillCurrent)
        assertTrue(old.isCancelled)
        assertEquals(listOf("new"), publications)
    }

    @Test fun `replacement cancels scan waiting for custom cover mutex instead of traversing obsolete roots`() = runBlocking {
        val requests = LibraryScanRequests(this)
        val mutex = Mutex(locked = true)
        val traversed = mutableListOf<String>()
        val old = requests.launch { mutex.withLock { traversed += "old" } }
        yield()
        val latest = requests.launch { mutex.withLock { traversed += "new" } }
        yield()
        mutex.unlock()
        old.join()
        latest.join()
        assertTrue(old.isCancelled)
        assertEquals(listOf("new"), traversed)
    }

    @Test fun `removing all folders invalidates active request without needing replacement scan`() = runBlocking {
        val requests = LibraryScanRequests(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        val scan = requests.launch { request ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                request.ensureCurrent()
                published = true
            }
        }
        entered.await()
        requests.invalidate()
        release.complete(Unit)
        scan.join()
        assertTrue(scan.isCancelled)
        assertFalse(published)
    }
}
