package com.astarworks.astera.vfxpreview

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import kotlin.math.max

/** Camera-independent world-space geometry of one tick. */
sealed interface Prim {
    /** Cuboid; corner index is `i*4 + j*2 + k` for unit-cube coordinates (i, j, k). */
    data class Box(
        val corners: List<Vec3>,
        val rgb: Int,
        val translucent: Boolean,
        val glowArgb: Int?,
        val emissive: Boolean,
    ) : Prim

    /** Text quad; [size] is the display scale, 1 pixel of font = 1/40 block at scale 1. */
    data class Text(
        val center: Vec3,
        val rotation: Quaternion,
        val size: Vec3,
        val billboard: DisplayBillboard,
        val styled: StyledText,
        val glowArgb: Int?,
    ) : Prim

    /** Item sprite (centred on [center]); drawn as a blade-like shape with its name. */
    data class Item(
        val center: Vec3,
        val rotation: Quaternion,
        val size: Vec3,
        val billboard: DisplayBillboard,
        val itemKey: String,
        val rgb: Int,
        val glowArgb: Int?,
    ) : Prim

    data class Dot(val position: Vec3, val age: Int) : Prim
}

object SceneBuilder {
    private const val TEXT_PIXEL = 1.0 / 40.0

    fun build(replay: PlanReplay, tick: Int, shift: Vec3 = Vec3.ZERO): List<Prim> {
        val prims = ArrayList<Prim>()
        for (d in replay.live(tick)) {
            val t = d.transform
            when (val kind = d.kind) {
                is VfxElementKind.Block -> {
                    val sw = BlockColors.swatch(d.block ?: kind.blockKey)
                    prims += Prim.Box(
                        corners = boxCorners(t, shift),
                        rgb = sw.rgb,
                        translucent = sw.translucent,
                        glowArgb = d.glowArgb,
                        emissive = (d.brightness ?: 0) >= 12,
                    )
                }
                is VfxElementKind.Item -> prims += Prim.Item(
                    center = t.translation + shift,
                    rotation = t.rotation,
                    size = t.scale,
                    billboard = d.billboard,
                    itemKey = kind.itemKey,
                    rgb = BlockColors.itemRgb(kind.itemKey),
                    glowArgb = d.glowArgb,
                )
                is VfxElementKind.Text -> prims += Prim.Text(
                    center = t.translation + shift,
                    rotation = t.rotation,
                    size = t.scale,
                    billboard = d.billboard,
                    styled = MiniText.style(d.text ?: kind.text),
                    glowArgb = d.glowArgb,
                )
                is VfxElementKind.Particle -> Unit
                is VfxElementKind.Sprite -> error("sprite elements are resolved to text by VfxTimeline.plan")
            }
        }
        for (p in replay.particles(tick)) prims += Prim.Dot(p.position + shift, p.age)
        return prims
    }

    fun boxCorners(t: DisplayTransform, shift: Vec3): List<Vec3> = buildList {
        for (i in 0..1) for (j in 0..1) for (k in 0..1) {
            val local = Vec3(i * t.scale.x, j * t.scale.y, k * t.scale.z)
            add(t.translation + shift + t.rotation.rotate(local))
        }
    }

    /** Every world point of [prims], for camera framing. */
    fun points(prims: List<Prim>): List<Vec3> = prims.flatMap { p ->
        when (p) {
            is Prim.Box -> p.corners
            is Prim.Dot -> listOf(p.position)
            is Prim.Text -> {
                val r = max(p.size.x, p.size.y) * (if (p.styled.sprite != null) 0.4 else 0.2)
                listOf(p.center + Vec3(r, r, r), p.center - Vec3(r, r, r))
            }
            is Prim.Item -> {
                val r = max(p.size.x, p.size.y) * 0.5
                listOf(p.center + Vec3(r, r, r), p.center - Vec3(r, r, r))
            }
        }
    }

    internal fun textPixel(): Double = TEXT_PIXEL
}
