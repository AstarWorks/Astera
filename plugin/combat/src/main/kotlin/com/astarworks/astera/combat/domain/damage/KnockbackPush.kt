package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * A knockback request of a resolved hit: push the defender horizontally away from [awayFrom] (the
 * attacker's feet) with [strength] (blocks per second). The defender's own position is only known
 * at the gateway boundary, so the direction is resolved there.
 */
public data class KnockbackPush(
    val awayFrom: Vec3,
    val strength: Double,
)
