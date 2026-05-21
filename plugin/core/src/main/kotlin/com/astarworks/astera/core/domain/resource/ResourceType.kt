package com.astarworks.astera.core.domain.resource

/**
 * Per-weapon resource pool type (Wave 5k, ADR-0080, Annihilation vision).
 *
 * Astera = "銃剣魔法 Annihilation server" (per
 * `.claude/plans/idea-annihilation-vision.md`). The three combat archetypes
 * each carry an independent resource pool:
 *
 *  - **`Ammo`** — finite, replenished by [reload] action. Guns.
 *  - **`Stamina`** — Dark Souls-style swing budget; consumed by attacks,
 *    regenerates passively while idle. Melee weapons.
 *  - **`Mana`** — per-tick regen pool; consumed by spell cast. Magic items.
 *
 * The sealed shape is intentional: extending the catalogue (e.g. `Heat`,
 * `Charge`) needs a deliberate ADR because the HUD glyph, regen scheduler,
 * and balance YAML all branch on the variant. ADR-0080 §4 freezes the
 * Phase 1 list at these three.
 *
 * **Domain-pure** — no Bukkit, no Adventure, no JOML. Konsist
 * `Resource domain must not import Bukkit Paper Adventure (ADR-0080)`
 * enforces this on every file in this package.
 */
public sealed interface ResourceType {
    /** Gun ammunition. Finite, replenished via reload. */
    public data object Ammo : ResourceType

    /** Melee swing budget. Consumed on attack, regenerates while idle. */
    public data object Stamina : ResourceType

    /** Magic energy. Per-tick passive regen. */
    public data object Mana : ResourceType

    public companion object {
        /** All Phase 1 variants. Order: Ammo, Stamina, Mana. */
        public val all: List<ResourceType> = listOf(Ammo, Stamina, Mana)

        /**
         * Resolve a variant from its lowercase serial name (`"ammo"`,
         * `"stamina"`, `"mana"`). Returns `null` for unknown input.
         *
         * Used by YAML loaders (item spec, resource defaults). Case-
         * insensitive on input.
         */
        public fun fromKey(key: String): ResourceType? = when (key.lowercase()) {
            "ammo" -> Ammo
            "stamina" -> Stamina
            "mana" -> Mana
            else -> null
        }
    }
}

/**
 * Lowercase serial name for [ResourceType] — stable across YAML, log, HUD glyph
 * resolution.
 */
public val ResourceType.key: String
    get() = when (this) {
        ResourceType.Ammo -> "ammo"
        ResourceType.Stamina -> "stamina"
        ResourceType.Mana -> "mana"
    }
