package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.port.outbound.entity.INpcBodyMaterializer
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.HologramKind
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.economy.application.intent.MatchShopIntentHandler
import com.astarworks.astera.economy.domain.matchshop.MatchShopCatalog
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-match lifecycle of the in-match ore shop: at [MatchStarted] one shop NPC per `shop_npcs` entry of the match's stage
 * is spawned in the match world (stage-local coordinates plus the stage origin), its right click bound to
 * `matchshop.open` for its team; at [MatchEnded] the NPCs are despawned and every participant's leftover ore is taken
 * away (ore is match-scoped).
 */
public class MatchShopNpcService(
    private val stages: IStageRegistry,
    private val spawner: IEntitySpawner,
    private val interactions: INpcInteractionRouter,
    private val bodies: () -> INpcBodyMaterializer?,
    private val matches: IMatchRepository,
    private val worldOf: (MatchId) -> WorldId?,
    private val stock: () -> IOwnedItemStock?,
    private val catalog: () -> MatchShopCatalog,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val npcs = ConcurrentHashMap<MatchId, List<EntityHandle>>()
    private val participants = ConcurrentHashMap<MatchId, Set<PlayerId>>()

    public fun onStarted(event: MatchStarted) {
        val stage = stages.find(event.stageId) ?: return
        remember(event.matchId)
        if (stage.shopNpcs.isEmpty()) return
        val world = worldOf(event.matchId) ?: stage.world
        val nameKey = MessageKey(NPC_NAME_KEY)
        val handles = stage.shopNpcs.mapNotNull { npc ->
            val position = Vec3(npc.x + stage.origin.x, npc.y, npc.z + stage.origin.z)
            when (val spawned = spawner.spawn(world, position, SpawnSpec.DisplayNpc(HologramKind.Text(nameKey)))) {
                is Result.Success -> spawned.value.also { handle ->
                    interactions.bindRightClick(handle, GuiIntent(MatchShopIntentHandler.INTENT_OPEN, mapOf(MatchShopIntentHandler.PAYLOAD_TEAM to npc.team.value)))
                    bodies()?.materialize(handle, world, position, npc.yaw, 0f, nameKey)
                }
                is Result.Failure -> {
                    log.warn("match shop NPC spawn failed (stage {}, team {}): {}", stage.id.value, npc.team.value, spawned.error)
                    null
                }
            }
        }
        npcs[event.matchId] = handles
    }

    public fun onEnded(event: MatchEnded) {
        npcs.remove(event.matchId).orEmpty().forEach { handle ->
            interactions.unbind(handle)
            bodies()?.remove(handle)
            spawner.despawn(handle)
        }
        remember(event.matchId) // players who joined after the start event
        val owned = stock()
        val ores = catalog().offers.flatMap { it.price.keys }.toSet()
        if (owned != null) {
            for (player in participants.remove(event.matchId).orEmpty()) {
                for (ore in ores) {
                    val left = owned.count(player, ore)
                    if (left > 0) owned.remove(player, ore, left)
                }
            }
        }
    }

    private fun remember(matchId: MatchId) {
        val members = matches.findById(matchId)?.teams?.flatMap { it.members }?.toSet() ?: return
        participants.merge(matchId, members) { old, new -> old + new }
    }

    public companion object {
        public const val NPC_NAME_KEY: String = "astera.economy.matchshop.npc"
    }
}
