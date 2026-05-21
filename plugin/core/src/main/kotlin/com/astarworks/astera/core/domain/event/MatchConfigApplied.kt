package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.MatchConfig
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Fired when [com.astarworks.astera.core.application.port.outbound.IMatchConfigService.apply]
 * successfully pushes a config onto a match's world.
 *
 * Subscribers:
 * - **Combat plugin** — caches `config.damageMultiplier` for the match.
 * - **Match HUD** (Wave 5b + 6) — refreshes icon overlay (rain indicator,
 *   PvP-disabled badge, etc.).
 * - **Audit log** — records who applied the config and when.
 *
 * Wave 5n (ADR-0080).
 */
public data class MatchConfigApplied(
    val matchId: MatchId,
    val worldId: WorldId,
    val config: MatchConfig,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(atTick >= 0) { "atTick must be >= 0 (got $atTick)" }
    }
}
