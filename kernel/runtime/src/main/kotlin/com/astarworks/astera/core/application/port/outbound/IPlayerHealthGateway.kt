package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource

/**
 * Outbound port: admin / system direct mutation of player health attributes
 * (Wave 5k, ADR-0080).
 *
 * # Two-tier delineation (ADR-0080 §1)
 *
 * Astera has **two independent health-modification paths**:
 *
 *  1. **Combat pipeline** — Wave 5a `IDamageGateway`. Drives damage
 *     calculation, status effects, knockback, attribution; publishes
 *     `DamageDealt`. Bound to combat events; respects PvP rules, armor,
 *     reduction, etc.
 *  2. **Admin / system path (this port)** — instant, unconditional state
 *     mutation by an authority outside combat: admin command, respawn
 *     restore, heal-skill, consumable, scripted scenario. Bypasses combat
 *     resolution; carries a [HealthChangeSource] for audit.
 *
 * Mixing the two through a single interface produced the "is this damage
 * combat or scripted?" ambiguity that the listener tier had to special-case.
 * Splitting them at the type level makes the listener tier exhaustive without
 * any reflection or flag fields.
 *
 * # Tier vs `IAttributeContainer` (ADR-0080 §2)
 *
 * Wave 5e `IAttributeContainer` (ADR-0058) is the **Astera domain attribute
 * layer** — abstract numeric attributes (`max_hp`, `crit_chance`,
 * `armor_pen`) overlaid by modifiers and used by combat resolvers.
 *
 * This port is the **Bukkit runtime mutation layer** — it writes back to the
 * vanilla `AttributeInstance.GENERIC_MAX_HEALTH` so the client UI shows the
 * right hearts. In the InMemory impl the two are independent; the Wave 6a
 * `PaperPlayerHealthGateway` adapter is the single point where the two meet
 * (it consults `IAttributeContainer` first and then writes Bukkit).
 *
 * # TTL semantics
 *
 * [setRegenRate] is the only stateful mode. Auto-reset to `0.0` (no regen)
 * happens after `ttlTicks`; pass `Int.MAX_VALUE` for "indefinite until
 * [clearRegen]". Follows Wave 5g `IPlayerMovementGateway` precedent.
 */
public interface IPlayerHealthGateway {

    /**
     * Set [playerId]'s vanilla max-health attribute to [maxHp].
     *
     * Bukkit semantics (Wave 6a impl): wraps
     * `AttributeInstance.GENERIC_MAX_HEALTH`. The InMemory default stores it
     * in an internal map and emits [com.astarworks.astera.core.domain.event.PlayerMaxHealthChanged].
     *
     * Fails with [HealthMutationError.InvalidValue] when [maxHp] is <= 0.0
     * or not finite.
     */
    public fun setMaxHealth(playerId: PlayerId, maxHp: Double): Result<Unit, HealthMutationError>

    /**
     * Set [playerId]'s current HP to [hp] without combat resolution.
     *
     * Clamps to `[0, currentMaxHealth]`. Emits
     * [com.astarworks.astera.core.domain.event.PlayerHealthChanged] with the
     * given [source].
     *
     * Fails with [HealthMutationError.InvalidValue] when [hp] is negative or
     * not finite.
     */
    public fun setHealth(
        playerId: PlayerId,
        hp: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError>

    /**
     * Heal [playerId] by [amount] HP (clamped at max-health).
     *
     * Convenience for the common "add HP" case; equivalent to
     * `setHealth(playerId, getCurrentHealth(playerId) + amount, source)`.
     */
    public fun heal(
        playerId: PlayerId,
        amount: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError>

    /**
     * Inflict [amount] HP of direct damage **outside the combat pipeline**
     * (admin "/kill" command, void death, suicide, fall-from-build —
     * scripted-only).
     *
     * **NOT** routed through `IDamageGateway`: no armor calc, no status
     * trigger, no `DamageDealt` event. Use the combat path for any
     * gameplay-driven damage.
     *
     * Fails with [HealthMutationError.InvalidValue] when [amount] < 0.
     */
    public fun damage(
        playerId: PlayerId,
        amount: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError>

    /**
     * Configure passive regen of [hpPerSecond] for [ttlTicks] in-game ticks.
     *
     * Implementations schedule a repeating tick task that adds `hpPerSecond /
     * 20.0` HP per tick (subject to max-health clamping). The task
     * auto-clears at TTL expiry.
     *
     * Pass `ttlTicks = Int.MAX_VALUE` for "indefinite until [clearRegen]".
     *
     * Fails with [HealthMutationError.InvalidValue] when [hpPerSecond] is
     * negative or not finite, or when [ttlTicks] <= 0 and not MAX_VALUE.
     */
    public fun setRegenRate(
        playerId: PlayerId,
        hpPerSecond: Double,
        ttlTicks: Int,
    ): Result<Unit, HealthMutationError>

    /** Cancel any active regen for [playerId]. Idempotent. */
    public fun clearRegen(playerId: PlayerId)

    /**
     * Read [playerId]'s current HP from this gateway's view.
     *
     * Returns `null` when no value has been recorded (e.g. brand-new
     * InMemory store, player not online for the Paper adapter). Callers
     * should treat null as "vanilla default applies".
     */
    public fun getCurrentHealth(playerId: PlayerId): Double?

    /**
     * Read [playerId]'s current max-health from this gateway's view.
     *
     * `null` when no override has been set (vanilla default 20.0 applies).
     */
    public fun getMaxHealth(playerId: PlayerId): Double?
}

/** Errors returnable from [IPlayerHealthGateway] mutations. */
public sealed interface HealthMutationError {

    /** Numeric argument was negative, non-finite, or otherwise out of range. */
    public data class InvalidValue(val field: String, val value: Double) : HealthMutationError

    /** Backend rejected the mutation (Paper adapter: player offline / world unloaded). */
    public data class BackendUnavailable(val reason: String) : HealthMutationError
}
