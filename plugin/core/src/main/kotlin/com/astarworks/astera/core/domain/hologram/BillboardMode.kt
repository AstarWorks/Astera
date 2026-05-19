package com.astarworks.astera.core.domain.hologram

/**
 * Vendor-neutral mirror of Bukkit `org.bukkit.entity.Display.Billboard`
 * (Wave 5h, ADR-0074 §1).
 *
 * Worker α moved this enum from `combat.domain.projectile.BillboardMode` to
 * `core.domain.hologram.BillboardMode` so both projectiles and holograms can
 * reuse it. `DisplaySpec.kt` updates its import accordingly.
 */
public enum class BillboardMode {
    FIXED,
    VERTICAL,
    HORIZONTAL,
    CENTER,
}
