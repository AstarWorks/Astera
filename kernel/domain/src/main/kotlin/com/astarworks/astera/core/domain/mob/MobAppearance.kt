package com.astarworks.astera.core.domain.mob

import com.astarworks.astera.core.domain.entity.HologramKind

/**
 * Visual presentation overrides for a spawned [MobSpec] (Wave 5pve,
 * ADR-0085).
 *
 * Carries the **vendor-neutral** appearance shape — the adapter side
 * resolves to backend-specific calls (vanilla `EntityType` + name tag,
 * MythicMobs display, Display-family entity for itemDisplay variant).
 *
 * # Why reuse [HologramKind]
 *
 * Wave 5l shipped [HologramKind] as the abstraction for "what kind of
 * display entity should I render". Mobs whose "appearance" is actually a
 * Display entity (e.g. animated armour, custom 3D model carried by an
 * invisible armour stand) reuse the same vocabulary so the Paper adapter
 * has one rendering path. Vanilla mobs leave [displayKind] null and let the
 * adapter pick the underlying entity type from [MobSpec.baseEntityType].
 *
 * # Name-tag colour
 *
 * [nameTagColor] is an optional RGB tuple. `null` defers to adapter default
 * (usually white). Once Wave 5j (HUD) lands its `HudColor` vocabulary on
 * mainline, this field migrates to that type via supersede ADR.
 *
 * @property displayKind Optional [HologramKind] override (e.g. itemDisplay
 *   for custom-modelled mobs). `null` = use the vanilla entity model from
 *   [MobSpec.baseEntityType].
 * @property customNameVisible If true, the [MobSpec.displayName] always
 *   shows above the mob; if false, it shows only on cursor-over.
 * @property nameTagColor Optional `(r, g, b)` tint for the name tag.
 * @property glowing If true, the mob renders with the glowing outline effect.
 */
public data class MobAppearance(
    public val displayKind: HologramKind? = null,
    public val customNameVisible: Boolean = false,
    public val nameTagColor: MobNameTagColor? = null,
    public val glowing: Boolean = false,
) {
    public companion object {
        /** Vanilla appearance — name tag hidden, no glow, no custom model. */
        public val DEFAULT: MobAppearance = MobAppearance()
    }
}

/**
 * RGB colour for [MobAppearance.nameTagColor].
 *
 * Mob-local type to avoid forward-coupling with Wave 5j's
 * `core.domain.hud.HudColor` (not yet on mainline). Once Wave 5j lands,
 * [MobNameTagColor] becomes a `typealias` (or is removed via supersede ADR).
 *
 * Each component is in `0..255`. Validated at construction.
 */
public data class MobNameTagColor(
    public val r: Int,
    public val g: Int,
    public val b: Int,
) {
    init {
        require(r in 0..255) { "MobNameTagColor.r must be in 0..255: $r" }
        require(g in 0..255) { "MobNameTagColor.g must be in 0..255: $g" }
        require(b in 0..255) { "MobNameTagColor.b must be in 0..255: $b" }
    }

    public companion object {
        public val WHITE: MobNameTagColor = MobNameTagColor(255, 255, 255)
        public val RED: MobNameTagColor = MobNameTagColor(255, 64, 64)
    }
}
