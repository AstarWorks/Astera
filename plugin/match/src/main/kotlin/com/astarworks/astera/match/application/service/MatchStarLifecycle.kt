package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore

/**
 * Per-match generator and star bookkeeping. At [MatchStarted] every generator authored for the match's stage
 * (`content/generators/`, `match_stage_id`) is instantiated under the match so the charge scheduler drives it; at
 * [MatchEnded] the generators, the stockpiles and every player's star count are dropped. Nothing about stars is read
 * or kept after the match.
 */
public class MatchStarLifecycle(
    private val registry: IGeneratorRegistry,
    private val generators: IGeneratorRepository,
    private val store: IMatchStarStore,
    private val stockpiles: StarStockpileService,
    private val onInvalid: (generatorId: String, reason: String) -> Unit = { _, _ -> },
) {
    public fun onStarted(event: MatchStarted) {
        for (config in registry.all().filter { it.matchStageId == event.stageId.value }) {
            config.toDomain(event.matchId).fold(
                onSuccess = { generators.save(it) },
                onFailure = { onInvalid(config.id, it.toString()) },
            )
        }
    }

    public fun onEnded(event: MatchEnded) {
        generators.removeForMatch(event.matchId)
        stockpiles.clear(event.matchId)
        store.clear(event.matchId)
    }
}
