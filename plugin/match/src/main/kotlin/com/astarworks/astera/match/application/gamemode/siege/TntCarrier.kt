package com.astarworks.astera.match.application.gamemode.siege

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The carry rules of a single TNT charge, of carry-tnt: it is picked up while nobody carries it (at its spawn, or
 * where a fallen carrier dropped it), delivered by its carrier standing in a base that is not the carrier's own, and
 * returned to the spawn when a dropped charge is not picked up in time.
 */
public class TntCarrier {
    public var carrier: PlayerId? = null
        private set

    /** Where the charge lies after its carrier fell; `null` = at its spawn (or carried). */
    public var droppedAt: Vec3? = null
        private set

    /** Server tick the charge was dropped at. */
    public var droppedTick: Long = 0L
        private set

    /** Hands the charge to [player] when it is loose (at the spawn or dropped). */
    public fun pickUp(player: PlayerId): Boolean {
        if (carrier != null) return false
        carrier = player
        droppedAt = null
        return true
    }

    /** The carrier [victim] fell at [at]: the charge lies there from [tick] on. True when [victim] was the carrier. */
    public fun drop(victim: PlayerId, at: Vec3, tick: Long): Boolean {
        if (carrier != victim) return false
        carrier = null
        droppedAt = at
        droppedTick = tick
        return true
    }

    /** The charge lies loose at [at] from [tick] on (parked at a checkpoint after a drop timed out). */
    public fun placeAt(at: Vec3, tick: Long) {
        carrier = null
        droppedAt = at
        droppedTick = tick
    }

    /** True when [player] carries the charge. */
    public fun isCarrying(player: PlayerId): Boolean = carrier == player

    /** The charge is delivered or timed out: it is back at its spawn and loose. */
    public fun reset() {
        carrier = null
        droppedAt = null
    }
}
