package com.sbro.emucoreh.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class HomeLibraryProjectionPublisherTest {
    @Test fun expensiveLibraryProjectionLeavesOwnerResponsive() = runBlocking {
        val publisher = HomeLibraryProjectionPublisher<List<Int>>(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = Thread.currentThread()
        var computeThread: Thread? = null
        var displayed = emptyList<Int>()
        val job = publisher.publish(compute = {
            computeThread = Thread.currentThread()
            entered.complete(Unit)
            release.await()
            (14000 downTo 1).sorted()
        }, accept = { displayed = it })
        try {
            entered.await()
            var responsive = false
            launch { responsive = true }.join()
            assertTrue(responsive)
            assertNotSame(owner, computeThread)
            release.complete(Unit)
            job.join()
            assertEquals(14000, displayed.size)
            assertEquals(1, displayed.first())
        } finally { release.complete(Unit); job.join() }
    }

    @Test fun slowOldSearchCannotReplaceTheLatestFilterResult() = runBlocking {
        val publisher = HomeLibraryProjectionPublisher<String>(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var displayed = ""
        val old = publisher.publish(compute = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                "old search"
            }
        }, accept = { displayed = it })
        entered.await()
        try {
            publisher.publish({ "new platform and search" }, { displayed = it }).join()
            release.complete(Unit)
            old.join()
            assertEquals("new platform and search", displayed)
        } finally { release.complete(Unit); old.join() }
    }
}
