package com.sbro.emucoreh.data.boxart

/** Resolves a document grant for the native disc drivers without copying game bytes. */
internal object DiscScrapeAccess {
    fun <T : Any> read(
        path: String,
        prepareSaf: (String) -> String?,
        resolveLocal: (String) -> String?,
        scrape: (String) -> T?
    ): T? {
        if (!path.startsWith("content://")) return scrape(path)
        val safPath = prepareSaf(path)?.takeIf(String::isNotBlank)
        safPath?.let(scrape)?.let { return it }
        val localPath = resolveLocal(path)?.takeIf { it.isNotBlank() && it != safPath }
        return localPath?.let(scrape)
    }
}
