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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    public data class Spawned(val handle: IDisplayEntityGateway.DisplayHandle, val world: WorldId, val at: Vec3, val spec: DisplaySpec, val lines: List<String>, val tick: Long = 0L)

    /** Displays currently alive. */
    public val active: MutableMap<IDisplayEntityGateway.DisplayHandle, Spawned> = linkedMapOf()

    /**
     * Highest number of simultaneously live displays seen at any spawn. Sampling [active] after a whole tick
     * hides the overlap of a tick that spawns its new displays before the expired ones are removed, which is
     * exactly what a client sees (add packets arrive before the remove packets), so budgets use this.
     */
    public var peakActive: Int = 0
        private set

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

    /** A text restyle (background ARGB, text_opacity, optional text colour). */
    public data class Style(val handle: IDisplayEntityGateway.DisplayHandle, val tick: Long, val backgroundArgb: Int, val textOpacity: Int, val textRgb: Int?)

    public data class Teleport(val handle: IDisplayEntityGateway.DisplayHandle, val tick: Long, val at: Vec3, val teleportTicks: Int)

    /** Every transform update, in order. */
    public val updates: MutableList<Update> = mutableListOf()
    public val swaps: MutableList<Swap> = mutableListOf()
    public val teleports: MutableList<Teleport> = mutableListOf()
    public val styles: MutableList<Style> = mutableListOf()

    override fun spawn(world: WorldId, at: Vec3, spec: DisplaySpec, renderedLines: List<String>): IDisplayEntityGateway.DisplayHandle {
        val handle = IDisplayEntityGateway.DisplayHandle(UUID.randomUUID())
        spec.viewRange?.let { viewRanges[handle] = it }
        val record = Spawned(handle, world, at, spec, renderedLines, scheduler.currentTick())
        active[handle] = record
        peakActive = maxOf(peakActive, active.size)
        spawned += record
        val what = when (val kind = spec.kind) {
            is DisplayKind.Block -> "block ${kind.blockKey}"
            is DisplayKind.Item -> "item ${kind.itemId.value}"
            is DisplayKind.Text -> "text ${renderedLines.joinToString(" / ")}"
            is DisplayKind.SkinBone -> "skin_bone ${kind.bone.yamlName}${if (kind.ghost) " ghost" else ""}"
            DisplayKind.AnchorMainHand -> "main_hand_weapon"
        }
        recorder.recordEffect(scheduler.currentTick(), "display_spawn", at, "$what lifetime=${spec.lifetimeTicks}")
        recorder.recordDisplay(
            scheduler.currentTick(),
            buildJsonObject {
                put("op", "spawn")
                put("id", handle.id.toString())
                when (val kind = spec.kind) {
                    is DisplayKind.Block -> { put("kind", "block"); put("block", kind.blockKey) }
                    is DisplayKind.Item -> { put("kind", "item"); put("item", kind.itemId.value); put("mode", kind.displayMode.name.lowercase()) }
                    is DisplayKind.SkinBone -> { put("kind", "skin_bone"); put("bone", kind.bone.yamlName); if (kind.ghost) put("ghost", true) }
                    DisplayKind.AnchorMainHand -> { put("kind", "main_hand_weapon") }
                    is DisplayKind.Text -> {
                        put("kind", "text")
                        put("lines", buildJsonArray { renderedLines.forEach { add(JsonPrimitive(it)) } })
                        put("background", kind.backgroundArgb)
                        put("shadow", kind.shadow)
                        put("seeThrough", kind.seeThrough)
                        put("lineWidth", kind.lineWidth)
                    }
                }
                put("x", at.x); put("y", at.y); put("z", at.z)
                putTransform(spec.transform)
                put("billboard", spec.billboard.name.lowercase())
                put("brightness", spec.brightness)
                put("glow", spec.glowArgb)
                put("viewRange", spec.viewRange?.toDouble())
                put("lifetime", spec.lifetimeTicks)
            },
        )
        return handle
    }

    /** Displays riding a player (ADR-0276): handle -> the anchor. */
    public val attached: MutableMap<IDisplayEntityGateway.DisplayHandle, com.astarworks.astera.core.domain.player.PlayerId> = linkedMapOf()

    /** The last `view_range` set per display (staged displays start at 0). */
    public val viewRanges: MutableMap<IDisplayEntityGateway.DisplayHandle, Float> = linkedMapOf()

    override fun spawnAttached(
        world: WorldId,
        spec: DisplaySpec,
        renderedLines: List<String>,
        anchor: com.astarworks.astera.core.domain.player.PlayerId,
        offset: Vec3,
    ): IDisplayEntityGateway.DisplayHandle {
        val handle = spawn(world, offset, spec, renderedLines)
        attached[handle] = anchor
        return handle
    }

    override fun isAttached(handle: IDisplayEntityGateway.DisplayHandle, anchor: com.astarworks.astera.core.domain.player.PlayerId, world: WorldId): Boolean =
        attached[handle] == anchor && handle in active

    override fun setViewRange(handle: IDisplayEntityGateway.DisplayHandle, viewRange: Float) {
        viewRanges[handle] = viewRange
    }

    override fun update(handle: IDisplayEntityGateway.DisplayHandle, transform: DisplayTransform, interpolationTicks: Int) {
        val record = active[handle] ?: return
        updates += Update(handle, scheduler.currentTick(), transform, interpolationTicks)
        recorder.recordEffect(scheduler.currentTick(), "display_update", record.at, "${handle.id} over=$interpolationTicks scale=${transform.scale}")
        recorder.recordDisplay(
            scheduler.currentTick(),
            buildJsonObject { put("op", "update"); put("id", handle.id.toString()); putTransform(transform); put("over", interpolationTicks) },
        )
    }

    override fun setItemModel(handle: IDisplayEntityGateway.DisplayHandle, itemModel: String?) {
        val record = active[handle] ?: return
        swaps += Swap(handle, scheduler.currentTick(), "item_model", itemModel ?: "")
        recorder.recordEffect(scheduler.currentTick(), "display_swap", record.at, "${handle.id} item_model ${itemModel ?: "<held>"}")
    }

    override fun setBlock(handle: IDisplayEntityGateway.DisplayHandle, blockKey: String) {
        val record = active[handle] ?: return
        swaps += Swap(handle, scheduler.currentTick(), "block", blockKey)
        recorder.recordEffect(scheduler.currentTick(), "display_swap", record.at, "${handle.id} block $blockKey")
        recorder.recordDisplay(
            scheduler.currentTick(),
            buildJsonObject { put("op", "swap"); put("id", handle.id.toString()); put("kind", "block"); put("block", blockKey) },
        )
    }

    override fun setText(handle: IDisplayEntityGateway.DisplayHandle, renderedLines: List<String>) {
        val record = active[handle] ?: return
        swaps += Swap(handle, scheduler.currentTick(), "text", renderedLines.joinToString(" / "))
        recorder.recordEffect(scheduler.currentTick(), "display_swap", record.at, "${handle.id} text ${renderedLines.joinToString(" / ")}")
        recorder.recordDisplay(
            scheduler.currentTick(),
            buildJsonObject {
                put("op", "swap"); put("id", handle.id.toString()); put("kind", "text")
                put("lines", buildJsonArray { renderedLines.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    override fun setTextStyle(handle: IDisplayEntityGateway.DisplayHandle, backgroundArgb: Int, textOpacity: Int, textRgb: Int?) {
        val record = active[handle] ?: return
        styles += Style(handle, scheduler.currentTick(), backgroundArgb, textOpacity, textRgb)
        recorder.recordEffect(scheduler.currentTick(), "display_style", record.at, "${handle.id} bg=${Integer.toHexString(backgroundArgb)} opacity=$textOpacity")
    }

    override fun teleport(handle: IDisplayEntityGateway.DisplayHandle, at: Vec3, yaw: Float, pitch: Float, teleportTicks: Int) {
        if (handle !in active) return
        teleports += Teleport(handle, scheduler.currentTick(), at, teleportTicks)
        recorder.recordEffect(scheduler.currentTick(), "display_teleport", at, "${handle.id} over=$teleportTicks")
        recorder.recordDisplay(
            scheduler.currentTick(),
            buildJsonObject {
                put("op", "teleport"); put("id", handle.id.toString())
                put("x", at.x); put("y", at.y); put("z", at.z)
                put("yaw", yaw.toDouble()); put("pitch", pitch.toDouble()); put("over", teleportTicks)
            },
        )
    }

    override fun remove(handle: IDisplayEntityGateway.DisplayHandle) {
        val record = active.remove(handle) ?: return
        attached.remove(handle)
        recorder.recordEffect(scheduler.currentTick(), "display_remove", record.at, record.handle.id.toString())
        recorder.recordDisplay(scheduler.currentTick(), buildJsonObject { put("op", "remove"); put("id", handle.id.toString()) })
    }

    private fun JsonObjectBuilder.putTransform(t: DisplayTransform) {
        put("translation", buildJsonArray { add(JsonPrimitive(t.translation.x)); add(JsonPrimitive(t.translation.y)); add(JsonPrimitive(t.translation.z)) })
        put("rotation", buildJsonArray { add(JsonPrimitive(t.rotation.x)); add(JsonPrimitive(t.rotation.y)); add(JsonPrimitive(t.rotation.z)); add(JsonPrimitive(t.rotation.w)) })
        put("scale", buildJsonArray { add(JsonPrimitive(t.scale.x)); add(JsonPrimitive(t.scale.y)); add(JsonPrimitive(t.scale.z)) })
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
