package com.astarworks.astera.core.domain.resource

/**
 * Per-player resource type (Wave 5k, ADR-0080; merged by F2-2, ADR-0194).
 *
 * Two resources, not three. Founder: "Stamina = the hunger bar. Make mana and
 * stamina the same thing." So the old `Stamina` (swing budget) and `Mana`
 * (spell budget) are one **`Energy`** pool that every non-gun skill spends and
 * that regenerates passively; it is shown on the vanilla food bar. Guns keep
 * their own finite, per-weapon **`Ammo`**.
 *
 *  - **`Ammo`** - finite, replenished by a reload action. Guns.
 *  - **`Energy`** - one shared pool for melee swings and spells; per-second regen.
 *
 * The sealed shape is intentional: extending the catalogue needs a deliberate
 * ADR because the HUD, regen scheduler and balance YAML branch on the variant.
 *
 * **Domain-pure** - no Bukkit, no Adventure, no JOML. Konsist
 * `Resource domain must not import Bukkit Paper Adventure (ADR-0080)`
 * enforces this on every file in this package.
 */
public sealed interface ResourceType {
    /** Gun ammunition. Finite, replenished via reload. */
    public data object Ammo : ResourceType

    /** Shared melee / magic energy. Consumed on attack and cast, regenerates passively. */
    public data object Energy : ResourceType

    public companion object {
        /** All variants. Order: Ammo, Energy. */
        public val all: List<ResourceType> = listOf(Ammo, Energy)

        /**
         * Resolve a variant from its lowercase serial name (`"ammo"`,
         * `"energy"`). Returns `null` for unknown input (including the retired
         * `mana` / `stamina`: content was renamed in one sweep, no alias).
         * Case-insensitive on input.
         */
        public fun fromKey(key: String): ResourceType? = when (key.lowercase()) {
            "ammo" -> Ammo
            "energy" -> Energy
            else -> null
        }
    }
}

/** Lowercase serial name for [ResourceType] - stable across YAML, log, HUD. */
public val ResourceType.key: String
    get() = when (this) {
        ResourceType.Ammo -> "ammo"
        ResourceType.Energy -> "energy"
    }
