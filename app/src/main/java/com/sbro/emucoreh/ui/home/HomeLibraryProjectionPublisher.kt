package com.sbro.emucoreh.ui.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search, grouping and sorting can be expensive for large libraries. Only the newest result wins. */
internal class HomeLibraryProjectionPublisher<T>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var job: Job? = null
    private var request: Any? = null

    fun publish(compute: suspend () -> T, accept: (T) -> Unit): Job {
        job?.cancel()
        val ticket = Any().also { request = it }
        return scope.launch {
            val result = withContext(dispatcher) { compute() }
            if (request === ticket) accept(result)
        }.also { job = it }
    }
}
