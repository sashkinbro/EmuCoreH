package com.sbro.emucoreh.ui.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Called on the home dispatcher; request validity can also be read by scanner IO. */
internal class LibraryScanRequests(private val scope: CoroutineScope) {
    internal class Request {
        @Volatile var isCurrent: Boolean = true
            internal set

        fun ensureCurrent() {
            if (!isCurrent) throw CancellationException("Library scan superseded")
        }
    }

    private var current: Request? = null
    private var job: Job? = null

    fun invalidate() {
        current?.isCurrent = false
        job?.cancel()
        current = null
        job = null
    }

    fun launch(block: suspend (Request) -> Unit): Job {
        invalidate()
        val request = Request()
        current = request
        return scope.launch(start = CoroutineStart.LAZY) {
            request.ensureCurrent()
            block(request)
        }.also {
            job = it
            it.start()
        }
    }
}
