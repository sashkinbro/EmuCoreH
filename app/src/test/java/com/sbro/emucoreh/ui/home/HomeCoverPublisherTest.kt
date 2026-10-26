package com.sbro.emucoreh.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class HomeCoverPublisherTest {
    @Test fun cachedLibraryAppearsBeforeSlowCoverIoAndOwnerRemainsResponsive() = runBlocking {
        val publisher = HomeCoverPublisher<String>(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val displayed = mutableListOf<List<String>>()
        val owner = Thread.currentThread()
        var resolverThread: Thread? = null
        val job = publisher.publish(listOf("cached-game"), "library-a", displayed::add,
            resolve = {
                resolverThread = Thread.currentThread()
                entered.complete(Unit)
                release.await()
                listOf("resolved-game")
            }, publishResolved = { displayed += it; true })!!
        try {
            assertEquals("Cached games are usable without awaiting cover storage", listOf(listOf("cached-game")), displayed)
            entered.await()
            var heartbeat = false
            launch { heartbeat = true }.join()
            assertTrue("The owner dispatcher remains available while cover IO waits", heartbeat)
            assertNotSame(owner, resolverThread)
            release.complete(Unit)
            job.join()
            assertEquals(listOf(listOf("cached-game"), listOf("resolved-game")), displayed)
        } finally { release.complete(Unit); job.join() }
    }

    @Test fun aFinishedOldRootCannotOverwriteNewLibraryEvenWhenProviderIgnoresCancellation() = runBlocking {
        val publisher = HomeCoverPublisher<String>(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var displayed = emptyList<String>()
        val old = publisher.publish(listOf("old-cache"), "root-a", { displayed = it }, resolve = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                listOf("old-resolved")
            }
        }, publishResolved = { displayed = it; true })!!
        entered.await()
        val fresh = publisher.publish(listOf("new-cache"), "root-b", { displayed = it },
            resolve = { listOf("new-resolved") }, publishResolved = { displayed = it; true })!!
        fresh.join()
        release.complete(Unit)
        old.join()
        assertEquals(listOf("new-resolved"), displayed)
    }

    @Test fun searchAndSortRepublishResolvedSnapshotWithoutRepeatingCoverReads() = runBlocking {
        val publisher = HomeCoverPublisher<String>(this)
        var reads = 0
        var games = listOf("cached")
        val resolve: suspend (List<String>) -> List<String> = { reads++; listOf("resolved") }
        publisher.publish(games, "root", {}, resolve) { games = it; true }!!.join()
        publisher.publish(games, "root", {}, resolve) { games = it; true }?.join()
        assertEquals(1, reads)
        publisher.publish(games, "new-root", {}, resolve) { games = it; true }!!.join()
        assertEquals("Root change still resolves its own cover snapshot", 2, reads)
    }
}
