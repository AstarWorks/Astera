package com.astarworks.astera.core.domain.animation.player

import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Failure modes of [com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway]
 * (Wave 5animation, ADR-0113).
 *
 * Sealed so callers can branch on the exact failure reason without
 * crossing the raw-throwable boundary (Konsist rule "application layers
 * must not import raw JVM throwable types").
 */
public sealed class PlayerAnimationError {

    /** No such player is currently online / tracked by the gateway. */
    public data class UnknownPlayer(public val playerId: PlayerId) : PlayerAnimationError()

    /**
     * Player is online but the gateway has no animation handle for the
     * requested operation (e.g. trying to stop a sleep that wasn't
     * started). Idempotent operations should return Success instead;
     * this variant is reserved for cases where the caller needs to know
     * the prior state didn't exist.
     */
    public data class NoActiveAnimation(public val playerId: PlayerId) : PlayerAnimationError()

    /** Custom animation id is not registered with the animation catalog. */
    public data class UnknownCustomAnimation(public val animationId: CustomAnimationId) : PlayerAnimationError()

    /** Equip animation was requested for an item the registry doesn't know. */
    public data class UnknownItem(public val itemId: ItemId) : PlayerAnimationError()

    /**
     * Equip animation was requested for a slot that's incompatible with
     * the item (e.g. equipping a sword in the helmet slot via
     * `applyEquipAnimation`). Phase 1 in-memory impl does not enforce —
     * Paper adapter is expected to.
     */
    public data class IncompatibleEquipSlot(
        public val slot: EquipSlot,
        public val itemId: ItemId,
    ) : PlayerAnimationError()

    /** Duration was negative or otherwise invalid. */
    public data class InvalidDuration(public val detail: String) : PlayerAnimationError()

    /** Generic platform failure (Bukkit-side exception in the Paper adapter). */
    public data class Platform(public val detail: String) : PlayerAnimationError()
}
