package com.sbro.emucoreh.data.boxart

enum class CoverFetchMode { OFFLINE_ONLY, REPOSITORY_ONLY, FULL }

/** Separates the cheap catalog lookup from opening a disc or querying fallback services. */
internal object CoverFetchOrder {
    fun <T : Any> resolve(
        mode: CoverFetchMode,
        cached: T?,
        repository: () -> T?,
        disc: () -> T?,
        fallback: (T?) -> T?,
        onRetry: (BoxartRetryException) -> Unit
    ): T? {
        if (mode == CoverFetchMode.OFFLINE_ONLY) return cached ?: disc()
        var repositoryFailed = false
        val online = try {
            repository()
        } catch (failure: BoxartRetryException) {
            repositoryFailed = true
            onRetry(failure)
            null
        }
        if (online != null) return online
        if (mode == CoverFetchMode.REPOSITORY_ONLY) return cached
        val offline = cached ?: disc()
        if (repositoryFailed) return offline
        return try {
            fallback(offline) ?: offline
        } catch (failure: BoxartRetryException) {
            onRetry(failure)
            offline
        }
    }
}
