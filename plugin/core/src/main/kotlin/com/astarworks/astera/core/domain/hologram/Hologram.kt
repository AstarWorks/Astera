package com.astarworks.astera.core.domain.hologram

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Vendor-neutral aggregate for a Bukkit `Display`-entity-backed hologram
 * (Wave 5h, ADR-0074 §1).
 *
 * Fields mirror Bukkit's `Display` API field-for-field so the Wave 6b
 * `PaperHologramGateway` is a positional translation, not a re-design:
 *
 * | Astera field        | Bukkit `Display` field             |
 * |---------------------|------------------------------------|
 * | [transform]         | `Display.transformation`           |
 * | [billboard]         | `Display.billboard`                |
 * | [interpolation]     | `Display.interpolationDuration/Delay/teleportDuration` |
 * | [brightness]        | `Display.brightness`               |
 * | [viewRangeBlocks]   | `Display.viewRange` (blocks)       |
 * | [shadowRadius]      | `Display.shadowRadius`             |
 * | [shadowStrength]    | `Display.shadowStrength`           |
 * | [displayWidth]      | `Display.displayWidth`             |
 * | [displayHeight]     | `Display.displayHeight`            |
 * | [glowColor]         | `Display.glowColorOverride`        |
 * | [visibilityScope]   | per-player packet filtering (no direct Bukkit equivalent) |
 *
 * Defaults are Bukkit-defaults so callers who only care about position +
 * kind get a usable hologram without writing every field. `displayWidth`
 * and `displayHeight` defaulting to `0f` matches Bukkit's "use the entity
 * bounding box" sentinel — explicit non-zero values override.
 */
public data class Hologram(
    public val id: HologramId,
    public val worldId: WorldId,
    public val position: Vec3,
    public val kind: HologramKind,
    public val transform: HologramTransform = HologramTransform(),
    public val billboard: BillboardMode = BillboardMode.FIXED,
    public val visibilityScope: HologramVisibilityScope = HologramVisibilityScope.Everyone,
    public val interpolation: HologramInterpolation = HologramInterpolation(),
    public val brightness: HologramBrightness? = null,
    public val viewRangeBlocks: Float = 64f,
    public val shadowRadius: Float = 0f,
    public val shadowStrength: Float = 1f,
    public val displayWidth: Float = 0f,
    public val displayHeight: Float = 0f,
    public val glowColor: HologramColor? = null,
)
