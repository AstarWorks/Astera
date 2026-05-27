package com.astarworks.astera.core.domain.vanilla

/**
 * Sealed enumeration of vanilla Minecraft features that Astera content
 * deliberately overrides (Wave 5server-harden / ADR-0123).
 *
 * # Why a sealed class instead of an enum
 *
 * `suppress(feature)` semantics may diverge per-variant in future waves
 * (e.g. weather-cycle suppression carries a "freeze to forecast" option).
 * Sealed gives us room to add per-variant fields without breaking existing
 * call sites.
 *
 * # Inventory of suppressed features (Phase 1)
 *
 * Each variant explains the conflict it removes:
 *
 *  - [VanillaAdvancement]: vanilla auto-grant collides with Astera's own
 *    advancement system (ADR-0093).
 *  - [VanillaDeathMessage]: vanilla "X was slain by Y" collides with
 *    Astera's Killfeed (ADR-0105).
 *  - [VanillaXpBar]: vanilla XP bar collides with Custom Level / Norma HUD.
 *  - [VanillaHungerBar]: vanilla hunger bar collides with Custom Mana HUD.
 *  - [VanillaMobSpawning]: vanilla natural-mob spawning collides with
 *    Star Generator stage-defined spawn graph.
 *  - [VanillaRecipe]: vanilla recipes collide with `ItemSkillBinding` —
 *    crafted vanilla items have no skill metadata.
 *  - [VanillaWeatherCycle]: per-match world fixes weather; vanilla rain
 *    transitions would unseat the cinematic state.
 *  - [VanillaPortalTraversal]: vanilla nether/end portal handlers collide
 *    with Astera's portal system (ADR-0120).
 *
 * Concrete Bukkit suppression (Event.setCancelled / world rules) lands in
 * Wave 6b's `PaperVanillaFeatureSuppressor`. This wave ships the port +
 * registry only — `IVanillaFeatureSuppressor.isSuppressed(...)` lets domain
 * code branch on intent without a runtime dependency on Paper.
 */
public sealed class VanillaFeature {
    /** Stable string id used for log lines / config keys. */
    public abstract val id: String

    public object VanillaAdvancement : VanillaFeature() {
        override val id: String = "vanilla.advancement"
    }

    public object VanillaDeathMessage : VanillaFeature() {
        override val id: String = "vanilla.death-message"
    }

    public object VanillaXpBar : VanillaFeature() {
        override val id: String = "vanilla.xp-bar"
    }

    public object VanillaHungerBar : VanillaFeature() {
        override val id: String = "vanilla.hunger-bar"
    }

    public object VanillaMobSpawning : VanillaFeature() {
        override val id: String = "vanilla.mob-spawning"
    }

    public object VanillaRecipe : VanillaFeature() {
        override val id: String = "vanilla.recipe"
    }

    public object VanillaWeatherCycle : VanillaFeature() {
        override val id: String = "vanilla.weather-cycle"
    }

    public object VanillaPortalTraversal : VanillaFeature() {
        override val id: String = "vanilla.portal-traversal"
    }

    public companion object {
        /**
         * All features Astera knows about; useful for "/astera-debug vanilla"
         * tooling. Lazy so the companion-init does not race the per-variant
         * `object` initialisers (sealed + companion + nested-object cycle:
         * the eager `listOf` saw partially-initialised nulls).
         */
        public val all: List<VanillaFeature> by lazy {
            listOf(
                VanillaAdvancement,
                VanillaDeathMessage,
                VanillaXpBar,
                VanillaHungerBar,
                VanillaMobSpawning,
                VanillaRecipe,
                VanillaWeatherCycle,
                VanillaPortalTraversal,
            )
        }
    }
}
