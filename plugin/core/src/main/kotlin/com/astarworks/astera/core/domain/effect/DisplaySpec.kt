package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Vendor-neutral description of a Paper 1.19.4+ Display Entity (block,
 * item, or text) spawned by `Mechanic.SpawnDisplay`. The adapter layer
 * converts this into the appropriate `org.bukkit.entity.Display` subclass
 * (`BlockDisplay`, `ItemDisplay`, `TextDisplay`).
 *
 * Self-contained: no Bukkit imports. Three transform components
 * (translation, rotation as a quaternion, scale) match Minecraft's
 * `transformation` data: identity quaternion = `(0,0,0,1)` per Mojang's
 * convention (x, y, z, w).
 *
 * Wave _skills-2 / ADR-0077.
 */
public data class DisplaySpec(
    public val kind: DisplayKind,
    public val transform: DisplayTransform = DisplayTransform.IDENTITY,
    public val lifetimeTicks: Long,
    public val billboard: DisplayBillboard = DisplayBillboard.FIXED,
) {
    init {
        require(lifetimeTicks > 0L) { "DisplaySpec.lifetimeTicks must be > 0: $lifetimeTicks" }
    }
}

/**
 * The content carried by a [DisplaySpec]. Sealed so the adapter dispatches
 * by `when` without reflection.
 */
public sealed class DisplayKind {
    /** Renders a block model. `blockKey` is a vanilla / namespaced block id (e.g. `minecraft:stone`). */
    public data class Block(public val blockKey: String) : DisplayKind() {
        init { require(blockKey.isNotBlank()) { "DisplayKind.Block.blockKey must not be blank" } }
    }

    /** Renders an item model. Resolves through `IItemRegistry`. */
    public data class Item(public val itemId: ItemId) : DisplayKind()

    /**
     * Renders floating text. `lines` is i18n-aware: the adapter picks the
     * viewer's locale per the same path used by chat / titles.
     */
    public data class Text(
        public val lines: List<LocalizedText>,
        public val backgroundArgb: Int = 0x40000000.toInt(),
    ) : DisplayKind() {
        init { require(lines.isNotEmpty()) { "DisplayKind.Text.lines must not be empty" } }
    }
}

/**
 * Affine transformation applied to the display, in local-to-world space.
 *
 *  - [translation]: positional offset relative to the spawn anchor.
 *  - [rotation]: orientation as a quaternion `(x, y, z, w)` — identity =
 *    `(0, 0, 0, 1)`. Mirrors Minecraft's `transformation.rotation` field.
 *  - [scale]: per-axis scale; defaults to unit scale.
 */
public data class DisplayTransform(
    public val translation: Vec3 = Vec3.ZERO,
    public val rotation: Quaternion = Quaternion.IDENTITY,
    public val scale: Vec3 = ONE_SCALE,
) {
    public companion object {
        public val ONE_SCALE: Vec3 = Vec3(1.0, 1.0, 1.0)
        public val IDENTITY: DisplayTransform = DisplayTransform()
    }
}

/**
 * Billboard rotation mode. Matches Mojang's `BillboardConstraints` enum
 * one-for-one so the adapter is a trivial mapping.
 */
public enum class DisplayBillboard { FIXED, VERTICAL, HORIZONTAL, CENTER }
