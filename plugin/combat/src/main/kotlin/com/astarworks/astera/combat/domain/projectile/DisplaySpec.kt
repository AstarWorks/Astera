package com.astarworks.astera.combat.domain.projectile

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.BillboardMode

/**
 * Minimal vendor-neutral description of a Bukkit Display entity used as a
 * projectile's visual carrier.
 *
 * Phase 2 mid scope: enough to render a custom-textured 3D mesh moving
 * through space. Richer features (text glow, interpolation duration,
 * brightness override) live on [com.astarworks.astera.core.domain.hologram.Hologram]
 * — projectiles use the slimmer DisplaySpec for in-flight visuals; static
 * holograms use the full [com.astarworks.astera.core.domain.hologram.Hologram]
 * aggregate (ADR-0079 §1).
 *
 * Wave 5h: [BillboardMode] moved to `core.domain.hologram` so the same enum
 * serves both projectile visuals and hologram displays.
 */
public data class DisplaySpec(
    val variant: DisplayVariant,
    /** Material / item / block key — `minecraft:diamond_sword` etc. */
    val materialKey: String,
    val scale: Vec3 = Vec3(1.0, 1.0, 1.0),
    val billboard: BillboardMode = BillboardMode.FIXED,
) {
    init {
        require(materialKey.isNotBlank()) { "materialKey must not be blank" }
    }
}

public enum class DisplayVariant {
    ITEM_DISPLAY,
    BLOCK_DISPLAY,
    TEXT_DISPLAY,
}
