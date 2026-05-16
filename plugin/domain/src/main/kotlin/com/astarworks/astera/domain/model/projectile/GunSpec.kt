package com.astarworks.astera.domain.model.projectile

import kotlin.time.Duration

/**
 * Definition of a gun-archetype weapon.
 *
 * A gun = magazine size + reload cycle + per-shot cooldown + the
 * [ProjectileSpec] it fires. Everything else (recoil, accuracy, scope) is
 * Phase 2 mid late additions — the schema stays minimal until needed.
 */
public data class GunSpec(
    val ammoCapacity: Int,
    val reloadDuration: Duration,
    val shotCooldown: Duration,
    val projectile: ProjectileSpec,
) {
    init {
        require(ammoCapacity > 0) { "ammoCapacity must be > 0, got $ammoCapacity" }
        require(reloadDuration.inWholeMilliseconds >= 0) { "reloadDuration must be >= 0" }
        require(shotCooldown.inWholeMilliseconds >= 0) { "shotCooldown must be >= 0" }
    }
}
