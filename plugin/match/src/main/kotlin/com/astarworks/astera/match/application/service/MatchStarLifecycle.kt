package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore

/**
 * Per-match generator and star bookkeeping. At [MatchStarted] every generator authored for the match's stage
 * (`content/generators/`, `match_stage_id`) is instantiated under the match so the charge scheduler drives it; at
 * [MatchEnded] the generators, the star drops lying on the map, every player's star count and Norma state are dropped. Nothing about stars is read
 * or kept after the match.
 */
public class MatchStarLifecycle(
    private val registry: IGeneratorRegistry,
    private val generators: IGeneratorRepository,
    private val store: IMatchStarStore,
    private val drops: StarDropService,
    private val norma: NormaLevelService,
    private val stages: com.astarworks.astera.core.application.port.outbound.IStageRegistry? = null,
    private val onInvalid: (generatorId: String, reason: String) -> Unit = { _, _ -> },
) {
    public fun onStarted(event: MatchStarted) {
        val stage = stages?.find(event.stageId)
        val source = stage?.templateId ?: event.stageId
        val origin = stage?.origin ?: com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0)
        for (config in registry.all().filter { it.matchStageId == source.value }) {
            val placed = config.copy(position = config.position.copy(x = config.position.x + origin.x, z = config.position.z + origin.z))
            placed.toDomain(event.matchId).fold(
                onSuccess = { generators.save(it) },
                onFailure = { onInvalid(config.id, it.toString()) },
            )
        }
    }

    public fun onEnded(event: MatchEnded) {
        generators.removeForMatch(event.matchId)
        drops.clear(event.matchId)
        norma.clear(event.matchId)
        store.clear(event.matchId)
    }
}
