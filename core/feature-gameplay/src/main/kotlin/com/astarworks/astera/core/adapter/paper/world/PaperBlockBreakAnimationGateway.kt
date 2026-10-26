package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.block.BlockBreakAnimationError
import com.astarworks.astera.core.domain.animation.block.BlockBreakStage
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import org.bukkit.Location
import org.bukkit.Server

/**
 * Crack overlay on a block via `Player.sendBlockDamage` (the vanilla break-stage packet), shown to the given viewers
 * or, when none are given, to every player of that world within [VIEW_RADIUS] blocks.
 */
public class PaperBlockBreakAnimationGateway(private val server: Server) : IBlockBreakAnimationGateway {

    private data class Key(val world: WorldId, val x: Int, val y: Int, val z: Int)

    private val stages = ConcurrentHashMap<Key, BlockBreakStage>()

    override fun setBreakStage(
        world: WorldId,
        position: Vec3,
        stage: BlockBreakStage,
        broadcastViewers: Set<PlayerId>?,
    ): Result<Unit, BlockBreakAnimationError> {
        val key = keyOf(world, position)
        if (stage == BlockBreakStage.RESET) stages.remove(key) else stages[key] = stage
        return send(key, if (stage == BlockBreakStage.RESET) 0f else (stage.ordinal + 0.5f) / 10f, broadcastViewers)
    }

    override fun cancelBreakAnimation(world: WorldId, position: Vec3): Result<Unit, BlockBreakAnimationError> {
        val key = keyOf(world, position)
        stages.remove(key) ?: return Result.success(Unit)
        return send(key, 0f, null)
    }

    override fun getCurrentStage(world: WorldId, position: Vec3): BlockBreakStage? = stages[keyOf(world, position)]

    private fun send(key: Key, progress: Float, viewers: Set<PlayerId>?): Result<Unit, BlockBreakAnimationError> {
        val bukkitWorld = server.getWorld(key.world.value)
            ?: return Result.failure(BlockBreakAnimationError.Platform("world ${key.world.value} not loaded"))
        val at = Location(bukkitWorld, key.x.toDouble(), key.y.toDouble(), key.z.toDouble())
        val targets = if (viewers != null) {
            viewers.mapNotNull { server.getPlayer(it.uuid) }.filter { it.world == bukkitWorld }
        } else {
            bukkitWorld.players.filter { it.location.distanceSquared(at) <= VIEW_RADIUS * VIEW_RADIUS }
        }
        targets.forEach { it.sendBlockDamage(at, progress) }
        return Result.success(Unit)
    }

    private fun keyOf(world: WorldId, p: Vec3): Key =
        Key(world, floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())

    private companion object {
        const val VIEW_RADIUS: Double = 48.0
    }
}
