package com.sbro.emucoreh.ui.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Called on the Home owner dispatcher; provider/filesystem work uses [ioDispatcher]. */
internal class HomeCoverPublisher<T>(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var lastSource: List<T>? = null
    private var lastLibraryKey: Any? = null
    private var request: Any? = null
    private var job: Job? = null

    fun publish(
        source: List<T>,
        libraryKey: Any?,
        publishCached: (List<T>) -> Unit,
        resolve: suspend (List<T>) -> List<T>,
        publishResolved: (List<T>) -> Boolean
    ): Job? {
        // Existing library data is independently usable while cover storage is slow.
        publishCached(source)
        if (source.isEmpty()) {
            job?.cancel()
            request = null
            lastSource = source
            lastLibraryKey = libraryKey
            return null
        }
        if (source === lastSource && libraryKey == lastLibraryKey) return job
        job?.cancel()
        val current = Any()
        request = current
        lastSource = source
        lastLibraryKey = libraryKey
        return scope.launch {
            try {
                val resolved = withContext(ioDispatcher) { resolve(source) }
                if (request === current) {
                    // The caller also checks its live source/root: it may have changed
                    // before another publication request reached the owner dispatcher.
                    lastSource = if (publishResolved(resolved)) resolved else null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request === current) lastSource = null // keep cached data and allow a retry
            }
        }.also { job = it }
    }
}
