package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Admin god mode (F1-D, ADR-0183): a god player is immune in Astera's damage
 * path. `ApplyDamageUseCase` consults [isGod] and rejects the attempt as
 * `TargetImmune` — the same outcome as the `immunity` status effect, but not
 * tied to a status duration. In-memory, per server session (cleared on restart).
 */
public interface IGodModeService {
    public fun isGod(playerId: PlayerId): Boolean

    public fun setGod(playerId: PlayerId, enabled: Boolean)

    /** Flip and return the new state. */
    public fun toggle(playerId: PlayerId): Boolean {
        val next = !isGod(playerId)
        setGod(playerId, next)
        return next
    }
}
