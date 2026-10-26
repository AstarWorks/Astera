package com.astarworks.astera.core.application.service.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchConfigService
import com.astarworks.astera.core.application.port.outbound.MatchConfigError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchConfigApplied
import com.astarworks.astera.core.domain.match.MatchConfig
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [IMatchConfigService] implementation.
 *
 * Lives in `application/service` (no Bukkit, no JDBC) so the same impl
 * serves the in-memory bootstrap and the per-match overlay logic regardless
 * of whether the world gateways underneath are in-memory or Paper-backed
 * (priority-100 overlay).
 *
 * Wave 5n (ADR-0080).
 */
public class InMemoryMatchConfigService(
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IMatchConfigService {

    /** Snapshot of (matchId, worldId, applied config) for resetToDefault. */
    private data class ActiveOverlay(val worldId: WorldId, val config: MatchConfig)

    private val active: MutableMap<MatchId, ActiveOverlay> = ConcurrentHashMap()

    override fun configOf(matchId: MatchId): MatchConfig =
        active[matchId]?.config ?: MatchConfig.DEFAULT

    override fun apply(
        matchId: MatchId,
        worldId: WorldId,
        config: MatchConfig,
    ): Result<Unit, MatchConfigError> {
        // Slots share a world. Rules are boot-owned; presentation is applied on each player's Entity scope.
        active[matchId] = ActiveOverlay(worldId, config)

        eventBus.publish(
            MatchConfigApplied(
                matchId = matchId,
                worldId = worldId,
                config = config,
                atTick = tickClock(),
            ),
        )

        return Result.success(Unit)
    }

    override fun resetToDefault(matchId: MatchId): MatchConfig {
        val overlay = active.remove(matchId) ?: return MatchConfig.DEFAULT
        return overlay.config
    }
}
