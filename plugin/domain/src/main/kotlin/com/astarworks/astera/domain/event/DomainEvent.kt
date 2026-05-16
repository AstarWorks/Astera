package com.astarworks.astera.domain.event

import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponId

/**
 * Marker for events produced by domain rules. Subclasses are immutable value
 * records. Application services publish events through an `IBroadcaster`
 * outbound port; subscribers live in the adapter layers.
 */
sealed interface DomainEvent

data class WeaponFired(
    val playerId: PlayerId,
    val weaponId: WeaponId,
    val at: Vec3,
) : DomainEvent
