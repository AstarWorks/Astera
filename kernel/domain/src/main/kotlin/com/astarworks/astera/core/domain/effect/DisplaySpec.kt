package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
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
    /** Fixed block + sky light (0..15) for an emissive look; `null` = world lighting. */
    public val brightness: Int? = null,
    /** ARGB outline glow colour; `null` = no glow. */
    public val glowArgb: Int? = null,
    /** Render-distance multiplier; `null` = vanilla default. */
    public val viewRange: Float? = null,
    /**
     * `true`: a weapon-rig part that stands in for the held item. The adapter sends the [WeaponRigShading]
     * light marker, which the pack's item shader lights like an outdoor held item with a lifted underside (ADR-0275).
     * Excludes [brightness].
     */
    public val weaponRigShading: Boolean = false,
    public val shaderEffect: String? = null,
) {
    init {
        require(brightness == null || brightness in 0..15) { "DisplaySpec.brightness must be 0..15: $brightness" }
        require(!(weaponRigShading && brightness != null)) { "DisplaySpec.weaponRigShading excludes brightness ($brightness)" }
        require(shaderEffect == null || (!weaponRigShading && brightness == null && kind is DisplayKind.Item)) {
            "shaderEffect requires an item display and excludes brightness/weapon shading"
        }
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

    /** Vanilla item material with an optional resource-pack item model override. */
    public data class Item(
        public val itemId: ItemId,
        public val displayMode: DisplayItemMode = DisplayItemMode.NONE,
        public val itemModel: String? = null,
    ) : DisplayKind()

    /**
     * One bone of the skin rig (ADR-0250, ADR-0276): the owner's skin on a `player_head` cube, remapped by the pack's core
     * shader. The adapter resolves the owner's profile; [slim] only selects the Alex arm marker. [ghost] draws the bone tinted and
     * translucent (ADR-0284): a purely visual afterimage, never a performer.
     */
    public data class SkinBone(public val bone: SkinRigBone, public val slim: Boolean = false, public val ghost: Boolean = false) : DisplayKind()

    /** The anchor's real main-hand stack in the `THIRDPERSON_RIGHTHAND` context (ADR-0276): the one weapon of a performance. */
    public data object AnchorMainHand : DisplayKind()

    /**
     * Renders floating text. `lines` is i18n-aware: the adapter picks the
     * viewer's locale per the same path used by chat / titles.
     */
    public data class Text(
        public val lines: List<LocalizedText>,
        public val backgroundArgb: Int = 0x40000000.toInt(),
        public val shadow: Boolean = false,
        public val seeThrough: Boolean = false,
        /** Wrap width in pixels; `null` = vanilla default (200). */
        public val lineWidth: Int? = null,
        /** `text_opacity` 0..255 (client treats < 4 as opaque). */
        public val textOpacity: Int = 255,
        /** `0xRRGGBB` text colour override; `null` = the lines' own colours. */
        public val textRgb: Int? = null,
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

/** How an item display positions its model; mirrors Mojang's `ItemDisplayContext` one-for-one. */
public enum class DisplayItemMode {
    NONE, THIRDPERSON_LEFTHAND, THIRDPERSON_RIGHTHAND, FIRSTPERSON_LEFTHAND, FIRSTPERSON_RIGHTHAND,
    HEAD, GUI, GROUND, FIXED,
}

/**
 * Billboard rotation mode. Matches Mojang's `BillboardConstraints` enum
 * one-for-one so the adapter is a trivial mapping.
 */
public enum class DisplayBillboard { FIXED, VERTICAL, HORIZONTAL, CENTER }
