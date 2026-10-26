package com.astarworks.astera.match.application.gamemode.siege

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The carry rules of a single TNT charge, shared by carry-tnt and the combined TNT of carry-mini-tnt: it is picked up
 * only while nobody carries it, delivered by its carrier entering a base that is not the carrier's own, and returned
 * when the carrier dies.
 */
public class TntCarrier {
    public var carrier: PlayerId? = null
        private set

    /** Hands the charge to [player] when it is loose. */
    public fun pickUp(player: PlayerId): Boolean {
        if (carrier != null) return false
        carrier = player
        return true
    }

    /** [player] (of [team]) entered the base of [baseTeam]: true when that delivers the charge. */
    public fun deliver(player: PlayerId, team: TeamId, baseTeam: TeamId): Boolean {
        if (carrier != player || baseTeam == team) return false
        carrier = null
        return true
    }

    /** True when [victim] was the carrier, i.e. the charge goes back to its spawn. */
    public fun lose(victim: PlayerId): Boolean {
        if (carrier != victim) return false
        carrier = null
        return true
    }

    public fun reset() {
        carrier = null
    }
}
