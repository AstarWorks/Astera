package com.astarworks.astera.core.domain.animation.entity

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Discriminator for [com.astarworks.astera.core.domain.event.MobAnimationPlayed] (Wave 5animation, ADR-0113).
 *
 * Sealed so subscribers can `when`-switch on the variant without falling
 * through to a default. Each variant carries the minimum payload the
 * Paper adapter needs to drive the corresponding vanilla animation:
 *
 *  - [Attack] — `EntityEvent.ARM_SWING` packet (id `2`), no payload.
 *  - [Death] — `EntityStatus.DEATH` packet (id `3`), no payload.
 *  - [Pickup] — `CollectItemPacket(itemEntityId, mobId, count)`; the
 *    [item] payload identifies the item picked up so subscribers (audio
 *    cue / loot-feed UI / quest counter) can react without round-tripping
 *    through the mob's inventory.
 *  - [DamageFlash] — `EntityEvent.PLAY_HURT_ANIMATION` (id `2` in newer
 *    protocols; adapter handles the protocol-version branch).
 *  - [Drown] — toggles the "drowning" pose; the [drowning] flag mirrors
 *    the state change ("started drowning" vs "stopped drowning").
 *  - [LookAt] — entity look-direction change; [direction] is the
 *    normalised vector the mob is now facing.
 */
public sealed interface MobAnimationKind {

    /** Vanilla mob attack swing (arm raise → swing → reset). */
    public data object Attack : MobAnimationKind

    /**
     * Death wobble animation. Optionally followed by a delayed despawn —
     * the despawn schedule is the caller's responsibility (canonical:
     * `IScheduler.run(20.ticks) { mobSpawner.despawn(handle) }`).
     */
    public data object Death : MobAnimationKind

    /**
     * Mob picked up an item entity (zombie picking up a sword, baby
     * villager picking up loot). The [item] identifies what was picked up.
     */
    public data class Pickup(public val item: ItemId) : MobAnimationKind

    /** Damage red-flash animation (used by every damage source, not just player hits). */
    public data object DamageFlash : MobAnimationKind

    /**
     * Drowning pose toggle.
     *
     * @property drowning `true` = mob entered drowning state; `false` =
     *   mob recovered (e.g. surfaced).
     */
    public data class Drown(public val drowning: Boolean) : MobAnimationKind

    /**
     * Mob look-direction update.
     *
     * @property direction normalised target direction (caller must pass a
     *   unit vector; the gateway validates).
     */
    public data class LookAt(public val direction: Vec3) : MobAnimationKind
}
