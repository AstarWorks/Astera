package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.ego.EgoConfig
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: the per-player E.G.O gauge (ADR-0231), `0..config.max`.
 *
 * Runtime-only state like [IResourcePoolService]: it fills on combat hits
 * (combat's `EgoGainOnDamageListener`), is spent by skills that declare
 * `ego_cost`, and is reset on death and match entry. Every change publishes
 * [com.astarworks.astera.core.domain.event.EgoGaugeChanged].
 */
public interface IEgoGauge {

    /** Tuning in force (`content/balance/ego.yaml`). */
    public val config: EgoConfig

    /** Current value; `0` for a player who never gained any. */
    public fun value(playerId: PlayerId): Int

    /** Add [amount] (clamped to `config.max`) and return the new value. Non-positive [amount] is ignored. */
    public fun gain(playerId: PlayerId, amount: Int): Int

    /** Subtract [cost]; fails with [EgoError.Insufficient] (gauge unchanged) when the player holds less. */
    public fun spend(playerId: PlayerId, cost: Int): Result<Int, EgoError>

    /** Tick of the player's last hit dealt or taken (the combat clock), or `null` if none since the last reset. */
    public fun lastCombatTick(playerId: PlayerId): Long?

    /** Back to 0 (death, match start). */
    public fun reset(playerId: PlayerId)

    /** Drain out-of-combat players one second's worth; called once per second by the wiring. */
    public fun decay(nowTick: Long)
}

/** Errors returnable from [IEgoGauge]. */
public sealed interface EgoError {
    /** Spend rejected because the gauge holds [available] but [requested] is needed. */
    public data class Insufficient(val available: Int, val requested: Int) : EgoError

    /** [requested] was negative. */
    public data class InvalidValue(val requested: Int) : EgoError
}
