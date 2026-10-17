package com.wallper.live.data.catalog

import com.wallper.live.core.Footage

/** Merged scenes; [complete] is false when part of the catalog could not be read. */
class Catalog(val items: List<Footage>, val complete: Boolean)

/** Where the gallery's footage comes from: Apple's feeds in the app, a fake in UI tests. */
fun interface CatalogSource {
    suspend fun load(forceRefresh: Boolean): Catalog
}
