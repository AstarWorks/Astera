package com.astarworks.astera.core.domain.perviewer

/**
 * Per-viewer attribute axis for an NPC override (Wave 5y-ext, ADR-0112).
 *
 * This sealed family describes a single scalar / boolean knob whose value can
 * differ **per viewer**. The packet adapter (Wave 6) translates each variant
 * into the corresponding Player Info Update / Entity Metadata packet.
 *
 * Domain-pure: no Bukkit, no kotlinx.serialization. Adding a new variant
 * here is a deliberate cross-cutting change — every per-viewer NPC adapter
 * must learn how to honour it before the variant can ship.
 *
 * # Variant semantics
 *
 *  - [Scale] — visual size multiplier via the Display transform or entity scale attribute.
 *  - [Glowing] — outline-shader toggle. Per-viewer because teammate vs.
 *    enemy spectator filters need to differ.
 *  - [Invisible] — render-suppress (the NPC stays spawned, viewers in
 *    "invisible" mode see no model). Distinct from
 *    `IPlayerVisibilityGateway.hideEntity` which drops the spawn packet
 *    entirely; `Invisible` keeps the entity tracked so equipment / name-
 *    tag overrides can still toggle back to visible cheaply.
 *  - [NameVisible] — nametag display on/off. Used by tutorial highlight
 *    flows and spy-style ability reveals.
 */
public sealed interface NpcAttributeKind {

    /** Visual scale multiplier. `1.0` resets to default. */
    public data class Scale(public val value: Double) : NpcAttributeKind {
        init {
            require(value > 0.0) { "NpcAttributeKind.Scale.value must be positive: $value" }
            require(value <= MAX_SCALE) {
                "NpcAttributeKind.Scale.value must be <= $MAX_SCALE: $value"
            }
        }

        public companion object {
            /** Upper bound matching vanilla Minecraft 1.20.5+ scale attribute cap. */
            public const val MAX_SCALE: Double = 16.0
        }
    }

    /** Outline shader on/off. */
    public data class Glowing(public val on: Boolean) : NpcAttributeKind

    /**
     * Render-suppress while keeping the entity tracked.
     *
     * Distinct from [com.astarworks.astera.core.application.port.outbound.visibility.IPlayerVisibilityGateway.hideEntity]
     * which drops the spawn packet entirely. `Invisible` keeps the handle
     * known to the viewer so subsequent overrides (skin, equipment, name)
     * can still be staged without a re-spawn round-trip.
     */
    public data class Invisible(public val on: Boolean) : NpcAttributeKind

    /** Nametag visibility on/off. */
    public data class NameVisible(public val on: Boolean) : NpcAttributeKind
}
