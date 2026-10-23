package com.astarworks.astera.core.domain.player.physiology

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Source / cause of a health-state mutation via [com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway].
 *
 * **Pure domain marker, no Bukkit imports** (ADR-0080).
 *
 * Distinguishes the "admin / system" mutation path (this gateway) from the
 * combat pipeline (Wave 5a `IDamageGateway` — `DamageSource.Weapon` /
 * `DamageSource.Environment`). When debugging "why did this player's HP
 * change", the [HealthChangeSource] variant tells you which subsystem
 * originated the mutation without the listener having to inspect the call
 * stack.
 *
 * Distinct from combat's `DamageSource` on purpose: the type system enforces
 * that the combat pipeline cannot accidentally route through this gateway
 * (and vice versa). The two layers can publish parallel domain events with
 * different `source` types, and listeners that care about one path only never
 * see noise from the other.
 */
public sealed interface HealthChangeSource {

    /** Admin command (`/astera-debug heal`). */
    public data object AdminCommand : HealthChangeSource

    /** Automatic regen tick driven by [com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway.setRegenRate]. */
    public data object Regen : HealthChangeSource

    /** Respawn or initial spawn restoration (Wave 5o respawn flow, future). */
    public data object Respawn : HealthChangeSource

    /** Healing potion / consumable item effect. */
    public data object Item : HealthChangeSource

    /** Skill effect (heal-on-cast, etc.). */
    public data class Skill(val skillId: String) : HealthChangeSource

    /** Status effect tick (regeneration / wither / etc.). */
    public data class Status(
        val statusId: String,
        /** Player who applied the status (DoT kill credit); null when environmental / unknown. */
        val originPlayerId: PlayerId? = null,
    ) : HealthChangeSource

    /** A vanilla entity attack accepted by the canonical health router (entity UUID may be a mob). */
    public data class Attack(val attackerId: java.util.UUID) : HealthChangeSource

    /** Catch-all for system / scripted mutations whose source is not modelled. */
    public data class Other(val label: String) : HealthChangeSource
}
