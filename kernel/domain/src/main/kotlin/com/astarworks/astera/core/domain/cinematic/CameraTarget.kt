package com.astarworks.astera.core.domain.cinematic

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * What the camera is currently looking at / locked onto (Wave 5cinematic,
 * ADR-0110).
 *
 * The Paper adapter (Wave 6) resolves each variant differently:
 *  - [Position] → static world coordinate; camera teleports there each tick.
 *  - [Entity] → mob / NPC / display; camera tracks the entity's position
 *    every tick (boss reveal cutscene).
 *  - [Player] → another player's position; used for spectator follow + duel
 *    cinematics.
 *  - [RelativeToPlayer] → fixed offset from the controlled player (shoulder
 *    cam, tutorial side-pan).
 *
 * Pure-domain by construction — Bukkit / packet types stay in the adapter.
 */
public sealed class CameraTarget {

    /** Static world coordinate. Camera locks onto this point until released. */
    public data class Position(public val position: Vec3) : CameraTarget()

    /**
     * Track a spawned entity. The Paper adapter resubscribes to the entity's
     * position each tick; on entity despawn the camera holds the last known
     * position (Wave 6 adapter decides whether to auto-release or fall back).
     */
    public data class Entity(public val entity: EntityHandle) : CameraTarget()

    /** Track another player's position (spectator-style follow). */
    public data class Player(public val player: PlayerId) : CameraTarget()

    /**
     * Fixed offset from the controlled player's own position. Used for
     * shoulder cam (e.g. `RelativeToPlayer(Vec3(0.0, 1.5, -2.0))`) and
     * tutorial side-pan reveals.
     */
    public data class RelativeToPlayer(public val offset: Vec3) : CameraTarget()
}
