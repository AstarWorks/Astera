package com.astarworks.astera.match.domain.star

import kotlin.time.Duration

/**
 * An exploration cache generator (`cache:` in `content/generators/`): each of the generator's `spots` holds one
 * one-shot bundle. The first player to walk within [openRadius] opens it; it refills [respawn] later.
 */
public data class StarCache(val respawn: Duration, val openRadius: Double) {
    init {
        require(respawn.isPositive()) { "cache.respawn_seconds must be positive" }
        require(openRadius > 0.0) { "cache.open_radius must be > 0 (was $openRadius)" }
    }
}
