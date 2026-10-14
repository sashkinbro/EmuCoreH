package com.sbro.emucoreh.data.boxart

internal class PreparedBoxartLookup {
    private val results = HashMap<String, TheGamesDbClient.GameInfo?>()
    private var prepared = false
    fun begin() { prepared = true }
    fun contains(uniqueId: String): Boolean = results.containsKey(uniqueId)
    fun add(batch: Map<String, TheGamesDbClient.GameInfo?>) { results.putAll(batch) }

    fun find(uniqueId: String, retryAtMillis: Long, fallback: () -> TheGamesDbClient.GameInfo?): TheGamesDbClient.GameInfo? {
        if (results.containsKey(uniqueId)) return results[uniqueId]
        if (prepared) throw BoxartRetryException(retryAtMillis)
        return fallback()
    }
}
