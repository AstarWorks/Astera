package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.ISummonGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.application.service.aoe.ExplosionDamageApplier
import com.astarworks.astera.combat.domain.aoe.ExplosionError
import com.astarworks.astera.combat.domain.aoe.ExplosionResult
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.combat.domain.event.aoe.ExplosionTriggered
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Simulator counterpart of `PaperExplosionGateway` (WP5): entity damage with
 * linear distance falloff through the production [ExplosionDamageApplier]
 * (so armor, friendly fire and the HP model all apply), radial knockback written
 * to the entity velocity, `ExplosionTriggered` on the bus, and an `explosion`
 * trace effect. Players and mobs alike. Terrain is not simulated: the block
 * policy is recorded in the effect detail instead of breaking blocks.
 */
public class SimExplosionGateway(
    private val world: SimWorld,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val damageApplier: ExplosionDamageApplier,
    private val isAlive: (PlayerId) -> Boolean,
    private val recorder: TraceRecorder,
) : IExplosionGateway {

    override fun trigger(spec: ExplosionSpec): Result<ExplosionResult, ExplosionError> {
        val affected = mutableListOf<Pair<PlayerId, Double>>()
        val base = spec.damageProfile.base
        if (spec.radius > 0.0) {
            for (entity in world.entities.toList()) {
                if (entity.world != spec.world || !isAlive(entity.id)) continue
                val body = entity.position + Vec3(0.0, entity.hitboxHeight / 2, 0.0)
                val offset = body - spec.center
                val distance = offset.length()
                val falloff = (1.0 - distance / spec.radius).coerceIn(0.0, 1.0)
                if (falloff <= 0.0) continue
                val applied = if (base > 0.0) damageApplier.apply(entity.id, base * falloff, spec) else 0.0
                affected += entity.id to applied
                if (spec.knockbackMultiplier > 0.0) {
                    val radial = if (distance > 0.0) offset.normalize() else Vec3(0.0, 1.0, 0.0)
                    entity.velocity = entity.velocity + radial * (spec.knockbackMultiplier * falloff)
                }
            }
        }
        recorder.recordEffect(
            scheduler.currentTick(),
            "explosion",
            spec.center,
            "radius=${spec.radius} damage=$base hit=${affected.size} blocks=${spec.blockDamage::class.simpleName}",
        )
        eventBus.publish(
            ExplosionTriggered(
                world = spec.world,
                center = spec.center,
                radius = spec.radius,
                source = spec.source,
                affectedPlayers = affected.toList(),
                affectedBlocks = 0,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(ExplosionResult(affected.toList(), 0))
    }
}

/** [IDisplayEntityGateway] that only records spawns and removals (displays have no gameplay state). */
public class SimDisplayGateway(
    private val scheduler: IScheduler,
    private val recorder: TraceRecorder,
) : IDisplayEntityGateway {

    public data class Spawned(val handle: IDisplayEntityGateway.DisplayHandle, val world: WorldId, val at: Vec3, val spec: DisplaySpec, val lines: List<String>)

    /** Displays currently alive. */
    public val active: MutableMap<IDisplayEntityGateway.DisplayHandle, Spawned> = linkedMapOf()

    /** Every display ever spawned, in order. */
    public val spawned: MutableList<Spawned> = mutableListOf()

    /** A transform update sent to a display (client-side interpolation over [interpolationTicks]). */
    public data class Update(
        val handle: IDisplayEntityGateway.DisplayHandle,
        val tick: Long,
        val transform: DisplayTransform,
        val interpolationTicks: Int,
    )

    /** A content swap (`kind` = `block` / `text`). */
    public data class Swap(val handle: IDisplayEntityGateway.DisplayHandle, val tick: Long, val kind: String, val value: String)

    public data class Teleport(val handle: IDisplayEntityGateway.DisplayHandle, val tick: Long, val at: Vec3, val teleportTicks: Int)

    /** Every transform update, in order. */
    public val updates: MutableList<Update> = mutableListOf()
    public val swaps: MutableList<Swap> = mutableListOf()
    public val teleports: MutableList<Teleport> = mutableListOf()

    override fun spawn(world: WorldId, at: Vec3, spec: DisplaySpec, renderedLines: List<String>): IDisplayEntityGateway.DisplayHandle {
        val handle = IDisplayEntityGateway.DisplayHandle(UUID.randomUUID())
        val record = Spawned(handle, world, at, spec, renderedLines)
        active[handle] = record
        spawned += record
        val what = when (val kind = spec.kind) {
            is DisplayKind.Block -> "block ${kind.blockKey}"
            is DisplayKind.Item -> "item ${kind.itemId.value}"
            is DisplayKind.Text -> "text ${renderedLines.joinToString(" / ")}"
        }
        recorder.recordEffect(scheduler.currentTick(), "display_spawn", at, "$what lifetime=${spec.lifetimeTicks}")
        return handle
    }

    override fun update(handle: IDisplayEntityGateway.DisplayHandle, transform: DisplayTransform, interpolationTicks: Int) {
        val record = active[handle] ?: return
        updates += Update(handle, scheduler.currentTick(), transform, interpolationTicks)
        recorder.recordEffect(scheduler.currentTick(), "display_update", record.at, "${handle.id} over=$interpolationTicks scale=${transform.scale}")
    }

    override fun setBlock(handle: IDisplayEntityGateway.DisplayHandle, blockKey: String) {
        val record = active[handle] ?: return
        swaps += Swap(handle, scheduler.currentTick(), "block", blockKey)
        recorder.recordEffect(scheduler.currentTick(), "display_swap", record.at, "${handle.id} block $blockKey")
    }

    override fun setText(handle: IDisplayEntityGateway.DisplayHandle, renderedLines: List<String>) {
        val record = active[handle] ?: return
        swaps += Swap(handle, scheduler.currentTick(), "text", renderedLines.joinToString(" / "))
        recorder.recordEffect(scheduler.currentTick(), "display_swap", record.at, "${handle.id} text ${renderedLines.joinToString(" / ")}")
    }

    override fun setBackground(handle: IDisplayEntityGateway.DisplayHandle, argb: Int) {
        val record = active[handle] ?: return
        val hex = "#%08X".format(argb)
        swaps += Swap(handle, scheduler.currentTick(), "background", hex)
        recorder.recordEffect(scheduler.currentTick(), "display_swap", record.at, "${handle.id} background $hex")
    }

    override fun teleport(handle: IDisplayEntityGateway.DisplayHandle, at: Vec3, yaw: Float, pitch: Float, teleportTicks: Int) {
        if (handle !in active) return
        teleports += Teleport(handle, scheduler.currentTick(), at, teleportTicks)
        recorder.recordEffect(scheduler.currentTick(), "display_teleport", at, "${handle.id} over=$teleportTicks")
    }

    override fun remove(handle: IDisplayEntityGateway.DisplayHandle) {
        val record = active.remove(handle) ?: return
        recorder.recordEffect(scheduler.currentTick(), "display_remove", record.at, record.handle.id.toString())
    }
}

/**
 * [ISummonGateway] that adds an inert [SimEntityKind.MOB] to the [SimWorld]
 * (it stands where it was summoned and carries the owner's team). Types in a
 * foreign namespace are refused like the Paper gateway refuses them.
 */
public class SimSummonGateway(
    private val world: SimWorld,
    private val scheduler: IScheduler,
    private val recorder: TraceRecorder,
) : ISummonGateway {

    private var counter: Int = 0

    override fun spawn(request: ISummonGateway.SummonRequest): PlayerId? {
        val key = request.entityTypeKey.trim().lowercase()
        if (':' in key && !key.startsWith("minecraft:")) return null
        val type = key.removePrefix("minecraft:")
        val entity = SimEntity(
            id = newPlayerId(),
            name = "summon-$type-${++counter}",
            kind = SimEntityKind.MOB,
            position = request.position,
            direction = Vec3(0.0, 0.0, 1.0),
            team = request.team,
            world = request.world,
        )
        world.add(entity)
        recorder.registerEntity(entity)
        recorder.recordEffect(scheduler.currentTick(), "summon", request.position, "$type owner=${request.owner.uuid}")
        return entity.id
    }

    override fun despawn(id: PlayerId) {
        val entity = world.find(id) ?: return
        world.remove(id)
        recorder.recordEffect(scheduler.currentTick(), "despawn", entity.position, entity.name)
    }
}
