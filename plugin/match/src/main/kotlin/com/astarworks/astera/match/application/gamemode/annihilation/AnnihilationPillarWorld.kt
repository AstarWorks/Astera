package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.NexusDamaged
import com.astarworks.astera.core.domain.event.NexusDestroyed
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * The world-facing side of Annihilation pillars (WP10), host-neutral
 * ([IWorldGateway] / [IPlayerGateway] only):
 *
 *  - [placeAll] puts each pillar's block into the world at match start;
 *  - a hit puffs particles + a sound at the pillar;
 *  - a destroyed pillar's block is removed with an explosion effect and a
 *    match-wide broadcast; a Tier 3 loss additionally announces the team's
 *    elimination;
 *  - [MatchEnded] clears the pillar blocks that are still standing.
 *
 * Pure presentation: all state lives in [AnnihilationMatchTracker].
 */
public class AnnihilationPillarWorld(
    private val tracker: AnnihilationMatchTracker,
    private val worldGateway: IWorldGateway,
    private val matches: IMatchRepository,
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
) {

    private data class Placed(val world: WorldId, val blocks: Map<String, Vec3>)

    /** Pillar blocks currently placed per match (nexus id -> block position), to clear at match end. */
    private val placed: MutableMap<MatchId, Placed> = mutableMapOf()

    /** Subscribe to the pillar and match events. Returns the handles for the wiring's cancel list. */
    public fun subscribe(eventBus: IDomainEventBus): List<IDomainEventBus.Subscription> = listOf(
        eventBus.subscribe(NexusDamaged::class) { onDamaged(it) },
        eventBus.subscribe(NexusDestroyed::class) { onDestroyed(it) },
        eventBus.subscribe(TeamEliminated::class) { onTeamEliminated(it) },
        eventBus.subscribe(MatchEnded::class) { clear(it.matchId) },
    )

    /** Places every pillar block of [matchId] (idempotent). No-op when the stage declared no pillars. */
    public fun placeAll(matchId: MatchId) {
        val sites = tracker.sitesOf(matchId) ?: return
        val blocks = mutableMapOf<String, Vec3>()
        for (view in tracker.pillarViews(matchId)) {
            val site = view.site ?: continue
            worldGateway.placeBlock(sites.world, site.position, site.block)
            blocks[view.nexus.id.value] = site.position
        }
        placed[matchId] = Placed(sites.world, blocks)
    }

    private fun onDamaged(event: NexusDamaged) {
        val (world, centre) = locate(event.matchId, event.nexusId.value) ?: return
        worldGateway.spawnParticle(world, centre, ParticleSpec(HIT_PARTICLE, 12, Vec3(0.3, 0.3, 0.3)))
        worldGateway.playSound(world, centre, SoundSpec(HIT_SOUND, 1.0, 1.0))
    }

    private fun onDestroyed(event: NexusDestroyed) {
        val where = locate(event.matchId, event.nexusId.value)
        if (where != null) {
            val (world, centre) = where
            val p = placed[event.matchId]
            p?.blocks?.get(event.nexusId.value)?.let { worldGateway.removeBlock(world, it) }
            worldGateway.spawnParticle(world, centre, ParticleSpec(DESTROY_PARTICLE, 1))
            worldGateway.playSound(world, centre, SoundSpec(DESTROY_SOUND, 1.5, 0.8))
        }
        broadcast(
            event.matchId,
            MSG_PILLAR_DESTROYED,
            mapOf("team" to event.owningTeam.value, "tier" to event.tier.ordinalValue.toString()),
        )
    }

    private fun onTeamEliminated(event: TeamEliminated) {
        broadcast(event.matchId, MSG_TEAM_ELIMINATED, mapOf("team" to event.teamId.value))
    }

    private fun clear(matchId: MatchId) {
        val p = placed.remove(matchId) ?: return
        p.blocks.values.forEach { worldGateway.removeBlock(p.world, it) }
    }

    private fun locate(matchId: MatchId, nexusId: String): Pair<WorldId, Vec3>? {
        val view = tracker.pillarViews(matchId).firstOrNull { it.nexus.id.value == nexusId } ?: return null
        val site = view.site ?: return null
        return (view.world ?: return null) to site.centre
    }

    private fun broadcast(matchId: MatchId, key: MessageKey, placeholders: Map<String, String>) {
        val match = matches.findById(matchId) ?: return
        for (member in match.teams.flatMap { it.members }) {
            playerGateway.sendMessage(member, messages.render(member, key, placeholders))
        }
    }

    public companion object {
        public val MSG_PILLAR_DESTROYED: MessageKey = MessageKey("astera.gamemode.annihilation.event.nexus_destroyed")
        public val MSG_TEAM_ELIMINATED: MessageKey = MessageKey("astera.gamemode.annihilation.event.team_eliminated")

        public const val HIT_PARTICLE: String = "minecraft:crit"
        public const val DESTROY_PARTICLE: String = "minecraft:explosion_emitter"
        public const val HIT_SOUND: String = "minecraft:block.anvil.land"
        public const val DESTROY_SOUND: String = "minecraft:entity.generic.explode"
    }
}
