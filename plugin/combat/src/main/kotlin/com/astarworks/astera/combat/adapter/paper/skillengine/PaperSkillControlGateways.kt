package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.combat.application.trace.SkillEngineTrace
import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway
import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import org.slf4j.LoggerFactory
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.effect.camera.ShakeDirection
import com.astarworks.astera.core.domain.effect.camera.ShakeProfile
import com.astarworks.astera.core.domain.effect.camera.ShakeShape
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.sprite.SpriteGlyphs
import com.astarworks.astera.core.domain.sprite.SpriteId
import com.astarworks.astera.core.domain.world.WorldId

/** `set_cooldown` op: starts the player's Astera cooldown for a skill id (the HUD and the cast gate read the same tracker). */
public class PaperSkillCooldownGateway(private val tracker: () -> ICooldownTracker?) : ISkillCooldownGateway {
    override fun set(entity: UUID, skillId: String, ticks: Int) {
        val t = tracker() ?: return
        if (ticks <= 0) t.reset(PlayerId(entity), CooldownKey.Skill(skillId))
        else t.start(PlayerId(entity), CooldownKey.Skill(skillId), ticks.toLong())
    }
}

/**
 * Caster body animation (`caster_anim`). A clip id in `content/animations` plays on the skin rig (the caster's own
 * skin on a Display rig, ADR-0250); the pose-only clips (`crouch`, `hide-main-hand`, `swing-main-hand`) go through the
 * player animation gateway and are held for `ticks`. Logs one trace line per play / stop while `astera skill trace` is on.
 */
public class PaperSkillCasterAnimGateway(
    private val rigs: () -> ISkinRigAnimator?,
    private val registry: () -> ISkinRigRegistry?,
    private val poses: () -> IPlayerAnimationGateway?,
) : ISkillCasterAnimGateway {
    private val log = LoggerFactory.getLogger("com.astarworks.astera.combat.skillengine.trace")

    override fun play(caster: UUID, anim: String, speed: Double, ticks: Int) {
        val id = CustomAnimationId(anim)
        val player = PlayerId(caster)
        if (registry()?.find(id) != null) {
            val result = rigs()?.play(player, id, speed)
            trace("caster_anim play $anim (skin rig, speed $speed) -> ${result ?: "no animator"}")
            return
        }
        val result = poses()?.playCustomAnimation(player, id, ticks.toLong().coerceAtLeast(1))
        if (result == null) log.error("[skill-error] caster_anim '{}': no player animation gateway, nothing plays", anim)
        trace("caster_anim play $anim (pose, $ticks ticks) -> ${result ?: "no gateway"}")
    }

    override fun stop(caster: UUID, anim: String) {
        val id = CustomAnimationId(anim)
        val rig = rigs() ?: return
        if (rig.active(PlayerId(caster)) != id) return
        rig.stop(PlayerId(caster))
        trace("caster_anim stop $anim")
    }

    override fun release(caster: UUID, anim: String) {
        if (registry()?.find(CustomAnimationId(anim))?.loop == true) stop(caster, anim)
    }

    private fun trace(message: String) {
        if (SkillEngineTrace.enabled) log.info("skill trace {}", message)
    }
}

/** Titles, action bar and camera shake through the combat feedback gateway; a shake is a zero-sum series of relative kicks. */
public class PaperSkillScreenGateway(
    private val feedback: IPlayerFeedbackGateway,
    private val scheduler: IScheduler,
    private val server: Server,
    private val displays: IDisplayEntityGateway,
    /** Cell size in pixels of a sprite (`content/sprites/<id>.yaml`); 8 (the 0.2-block base cell) when unknown. */
    private val spritePx: (String) -> Int = { 8 },
) : ISkillScreenGateway {
    private val liveVeils = ConcurrentHashMap<UUID, AtomicInteger>()

    /**
     * `screen_veil` (v1 ScreenVeilHandler): a CENTER-billboard text-display glyph quad `distance` blocks along the look direction
     * from the eye, brightness 15, view range 8, shown to the viewer only. Re-anchored every tick through 1-tick teleport
     * interpolation on the viewer's entity scope; opacity `(1 - age / ticks) * 255`; removed after `ticks`. At most 2 live per viewer.
     */
    override fun veil(viewer: UUID, sprite: String, colorRgb: Int, side: Double, distance: Double, ticks: Int) {
        val player = server.getPlayer(viewer) ?: return
        val counter = liveVeils.computeIfAbsent(viewer) { AtomicInteger() }
        if (counter.incrementAndGet() > MAX_LIVE_VEILS) {
            counter.decrementAndGet()
            return
        }
        val glyph = SpriteGlyphs.text(SpriteId(sprite), 0, colorRgb)
        val intrinsic = Intrinsic.textGlyph(spritePx(sprite))
        val write = toDisplayWrite(Frame(Vec3.ZERO), Vec3(side, side, 1.0), intrinsic, DisplayBillboard.CENTER, 0)
        val spec = SkillDisplaySpecs.plate(glyph, 1.0, write.transform, DisplayBillboard.CENTER, 15).copy(lifetimeTicks = (ticks + 2).toLong(), viewRange = 8f)
        val (at, yaw, pitch) = anchor(player, distance)
        val handle = displays.spawnFor(WorldId(player.world.name), at, spec, listOf(glyph), PlayerId(viewer))
        if (handle == null) {
            counter.decrementAndGet()
            LoggerFactory.getLogger(javaClass).error("[skill-error] screen_veil: display was not spawned for {}", viewer)
            return
        }
        var age = 0
        var task: IScheduler.TaskHandle? = null
        fun finish() {
            task?.cancel()
            displays.remove(handle)
            counter.decrementAndGet()
        }
        task = scheduler.repeat(ExecutionScope.Entity(viewer), ticks(1), ticks(1)) {
            age++
            val p = server.getPlayer(viewer)
            if (age > ticks || p == null) return@repeat finish()
            val (pos, y, pt) = anchor(p, distance)
            displays.moveTo(handle, pos, y, pt, 1)
            displays.setTextStyle(handle, 0, SkillDisplaySpecs.opacity(1.0 - age.toDouble() / ticks).coerceAtLeast(1), null)
        }
    }

    private fun anchor(player: org.bukkit.entity.Player, distance: Double): Triple<Vec3, Float, Float> {
        val eye = player.eyeLocation
        val d = eye.direction
        return Triple(Vec3(eye.x + d.x * distance, eye.y + d.y * distance, eye.z + d.z * distance), eye.yaw, eye.pitch)
    }

    override fun title(viewer: UUID, text: String, subtitle: String?, ticks: Int) {
        feedback.sendTitle(PlayerId(viewer), text, subtitle, 5, ticks.coerceAtLeast(1), 10)
    }

    override fun actionBar(viewer: UUID, text: String) {
        feedback.postActionBar(PlayerId(viewer), text)
    }

    override fun flash(viewer: UUID, overlay: com.astarworks.astera.core.domain.effect.ScreenOverlay, colorRgb: Int, fadeIn: Int, stay: Int, fadeOut: Int) {
        feedback.sendScreenFlash(PlayerId(viewer), overlay, colorRgb, fadeIn, stay, fadeOut)
    }

    override fun stopSound(viewer: UUID, sound: String) {
        feedback.stopSound(PlayerId(viewer), sound)
    }

    private companion object {
        const val MAX_LIVE_VEILS = 2
    }

    override fun cameraShake(viewer: UUID, intensity: Double, ticks: Int) {
        val seed = viewer.leastSignificantBits xor ticks.toLong()
        val pulses = ShakeProfile.plan(ShakeShape.DECAY, ShakeDirection.NONE, intensity, ticks, seed = seed)
        for (p in pulses) {
            if (p.tick <= 0) feedback.cameraKick(PlayerId(viewer), p.dx, p.dy)
            else scheduler.runLaterTicks(ExecutionScope.Entity(viewer), p.tick.toLong()) {
                feedback.cameraKick(PlayerId(viewer), p.dx, p.dy)
            }
        }
    }
}

/** `give_item` / `consume_item`: Astera items are found in the inventory by their PDC `item_id`. */
public class PaperSkillItemGateway(
    private val server: Server,
    private val build: (ItemId, PlayerId, Int) -> ItemStack?,
) : ISkillItemGateway {
    override fun give(entity: UUID, itemId: String, count: Int) {
        val player = server.getPlayer(entity) ?: return
        val stack = build(ItemId(itemId), PlayerId(entity), count) ?: run {
            LoggerFactory.getLogger(javaClass).error("[skill-error] give_item: item '{}' does not resolve to a stack", itemId)
            return
        }
        player.inventory.addItem(stack).values.forEach { player.world.dropItem(player.location, it) }
    }

    override fun consume(entity: UUID, itemId: String, count: Int): Int {
        val inv = server.getPlayer(entity)?.inventory ?: return 0
        var left = count
        for (slot in 0 until inv.size) {
            if (left <= 0) break
            val stack = inv.getItem(slot) ?: continue
            if (idOf(stack) != itemId) continue
            val take = minOf(left, stack.amount)
            if (take >= stack.amount) inv.setItem(slot, null) else stack.amount -= take
            left -= take
        }
        return count - left
    }

    private fun idOf(stack: ItemStack): String? {
        val pdc = stack.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == "item_id" } ?: return null
        return pdc.get(key, PersistentDataType.STRING)
    }
}
