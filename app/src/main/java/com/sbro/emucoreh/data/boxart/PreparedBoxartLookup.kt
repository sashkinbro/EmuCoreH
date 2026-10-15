package com.sbro.emucoreh.data.boxart

internal class PreparedBoxartLookup {
    private val results = HashMap<String, TheGamesDbClient.GameInfo?>()
    private val catalogSkipped = HashSet<String>()
    private var prepared = false
    @Synchronized fun begin() { prepared = true }
    @Synchronized fun contains(uniqueId: String): Boolean = results.containsKey(uniqueId)
    @Synchronized fun add(batch: Map<String, TheGamesDbClient.GameInfo?>) { results.putAll(batch) }
    @Synchronized fun skipForCatalog(uniqueId: String) { catalogSkipped.add(uniqueId) }

    @Synchronized
    fun find(uniqueId: String, retryAtMillis: Long, fallback: () -> TheGamesDbClient.GameInfo?): TheGamesDbClient.GameInfo? {
        if (results.containsKey(uniqueId)) return results[uniqueId]
        // This UID deliberately skipped batching because our catalog had art.
        // Only reached when that artwork was missing; cache a real fallback
        // result, while allowing transient exceptions to retain their retry.
        if (uniqueId in catalogSkipped) return fallback().also { results[uniqueId] = it }
        if (prepared) throw BoxartRetryException(retryAtMillis)
        return fallback()
    }
}
