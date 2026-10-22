package com.astarworks.astera.vfxpreview

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxElementPlan
import com.astarworks.astera.core.domain.effect.vfx.VfxPlan
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/** One display entity alive on a given tick, with its client-interpolated transform. */
data class LiveDisplay(
    val elementId: String,
    val slot: Int,
    val kind: VfxElementKind,
    /** Current block key (block kind) after content swaps; `null` for other kinds. */
    val block: String?,
    /** Current MiniMessage text (text kind) after content swaps; `null` for other kinds. */
    val text: String?,
    val transform: DisplayTransform,
    val glowArgb: Int?,
    val brightness: Int?,
    val billboard: DisplayBillboard,
    /** Entity position offset from the plan origin (quads move by teleport, not by transformation). */
    val offset: Vec3 = Vec3.ZERO,
    /** Current colour / alpha of a quad (after style updates), `null` for other kinds. */
    val quadRgb: Int? = null,
    val quadAlpha: Double = 1.0,
)

/**
 * A particle emission still visible, [age] ticks after it was emitted. [direction] is the element's
 * direction of travel (the `along_tangent` velocity), [trailTarget] the plan-relative `toward` point.
 */
data class LiveParticle(val position: Vec3, val age: Int, val direction: Vec3? = null, val trailTarget: Vec3? = null)

/**
 * Replays a [VfxPlan] the way a vanilla client would see it: a display is live from its spawn
 * tick until (excluding) its despawn tick, and every update restarts a linear interpolation
 * (translation / scale lerp, rotation slerp) from whatever was rendered at the moment the
 * update arrived, lasting `interpolationTicks` (with `interpolation_delay = 0`).
 */
class PlanReplay(val plan: VfxPlan) {

    fun live(tick: Int): List<LiveDisplay> = plan.elements
        .filter { it.kind !is VfxElementKind.Particle && tick >= it.spawnTick && tick < it.despawnTick }
        .map { liveOf(it, tick) }

    fun liveCount(tick: Int): Int = live(tick).size

    fun particles(tick: Int, lifetime: Int = PARTICLE_LIFETIME_TICKS): List<LiveParticle> = plan.elements
        .flatMap { el -> el.particles }
        .filter { it.tick <= tick && tick - it.tick < lifetime }
        .flatMap { e ->
            // along_path elements emit the whole streak between the previous and current position
            e.pathOffsets.ifEmpty { listOf(e.offset) }.map { LiveParticle(it, tick - e.tick, e.tangent, e.trailTarget) }
        }

    private fun liveOf(el: VfxElementPlan, tick: Int): LiveDisplay {
        var from = el.initial
        var to = el.initial
        var start = el.spawnTick
        var duration = 1
        var posFrom = el.spawnOffset
        var posTo = el.spawnOffset
        var posStart = el.spawnTick
        var posDuration = 1
        val quad = el.kind as? VfxElementKind.Quad
        var rgb = quad?.colorRgb
        var alpha = quad?.alpha ?: 1.0
        var block = (el.kind as? VfxElementKind.Block)?.blockKey
        var text = (el.kind as? VfxElementKind.Text)?.text
        for (u in el.updates) {
            if (u.tick > tick) break
            from = interpolate(from, to, start, duration, u.tick)
            to = u.transform
            start = u.tick
            duration = u.interpolationTicks.coerceAtLeast(1)
            u.block?.let { block = it }
            u.text?.let { text = it }
            u.position?.let {
                posFrom = lerp(posFrom, posTo, ((u.tick - posStart).toDouble() / posDuration).coerceIn(0.0, 1.0))
                posTo = it
                posStart = u.tick
                posDuration = u.interpolationTicks.coerceAtLeast(1)
            }
            u.style?.let {
                if (quad?.glyph == null) alpha = (it.backgroundArgb ushr 24) / 255.0 else alpha = it.textOpacity / 255.0
                rgb = it.textRgb ?: (it.backgroundArgb and 0xFFFFFF)
            }
        }
        return LiveDisplay(
            elementId = el.elementId,
            slot = el.slot,
            kind = el.kind,
            block = block,
            text = text,
            transform = interpolate(from, to, start, duration, tick),
            glowArgb = el.glowArgb,
            brightness = el.brightness,
            billboard = el.billboard,
            offset = lerp(posFrom, posTo, ((tick - posStart).toDouble() / posDuration).coerceIn(0.0, 1.0)),
            quadRgb = rgb,
            quadAlpha = alpha,
        )
    }

    private fun interpolate(from: DisplayTransform, to: DisplayTransform, start: Int, duration: Int, tick: Int): DisplayTransform {
        val f = ((tick - start).toDouble() / duration).coerceIn(0.0, 1.0)
        if (f >= 1.0) return to
        if (f <= 0.0) return from
        return DisplayTransform(
            translation = lerp(from.translation, to.translation, f),
            rotation = Quaternion.slerp(from.rotation, to.rotation, f),
            scale = lerp(from.scale, to.scale, f),
        )
    }

    private fun lerp(a: Vec3, b: Vec3, f: Double): Vec3 = a + (b - a) * f

    companion object {
        const val PARTICLE_LIFETIME_TICKS: Int = 4
    }
}

/** Tint of a MiniMessage-ish text plus its visible characters. */
data class StyledText(val text: String, val rgb: Int, val bold: Boolean, val sprite: SpriteRef? = null)

object MiniText {
    private val tag = Regex("<[^>]*>")
    private val hexTag = Regex("<#([0-9a-fA-F]{6})>")
    private val named = mapOf(
        "white" to 0xFFFFFF, "red" to 0xFF5555, "dark_red" to 0xAA0000, "gold" to 0xFFAA00, "yellow" to 0xFFFF55,
        "green" to 0x55FF55, "aqua" to 0x55FFFF, "blue" to 0x5555FF, "light_purple" to 0xFF55FF, "gray" to 0xAAAAAA,
        "dark_gray" to 0x555555, "black" to 0x000000,
    )

    private val spriteFont = Regex("<font:astera:vfx/([a-z0-9-]+)>\\s*([\\uE000-\\uE0FF])")

    fun style(raw: String): StyledText {
        val sprite = spriteFont.find(raw)?.let { SpriteRef(it.groupValues[1], it.groupValues[2][0].code - 0xE000) }
        val hex = hexTag.find(raw)?.groupValues?.get(1)?.toInt(16)
        val name = Regex("<([a-z_]+)>").findAll(raw).map { named[it.groupValues[1]] }.firstOrNull { it != null }
        return StyledText(
            text = raw.replace(tag, ""),
            rgb = hex ?: name ?: 0xFFFFFF,
            bold = raw.contains("<b>") || raw.contains("<bold>"),
            sprite = sprite,
        )
    }
}
