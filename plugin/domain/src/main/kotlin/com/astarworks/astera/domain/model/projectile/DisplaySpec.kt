package com.astarworks.astera.domain.model.projectile

import com.astarworks.astera.domain.model.geometry.Vec3

/**
 * Minimal vendor-neutral description of a Bukkit Display entity used as a
 * projectile's visual carrier.
 *
 * Phase 2 mid scope: enough to render a custom-textured 3D mesh moving
 * through space. Richer features (text glow, interpolation duration,
 * brightness override) get added when actually needed.
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

public enum class BillboardMode {
    FIXED,
    VERTICAL,
    HORIZONTAL,
    CENTER,
}
