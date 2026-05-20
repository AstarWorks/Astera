package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchConfig
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: per-match [MatchConfig] overlay.
 *
 * Wave 5n (ADR-0080) — the bridge between the Wave 5c match mode framework
 * and the world-environment ports introduced in this wave. Match start use
 * cases call [apply] to push the config onto the underlying world; match
 * end calls [resetToDefault] to lift the overlay.
 *
 * The service does NOT own the world state itself — it delegates to
 * [IWorldRulesGateway], [IWorldTimeGateway], [IWorldWeatherGateway] inside
 * the implementation. Tests can construct it with Fake gateways and assert
 * the recorded calls.
 *
 * ## MatchConfigApplied event
 *
 * Successful [apply] also publishes
 * [com.astarworks.astera.core.domain.event.MatchConfigApplied] via the
 * default `IDomainEventBus` so other plugins (Combat for damage multiplier,
 * Match HUD for icon overlay) can react.
 */
public interface IMatchConfigService {

    /**
     * Returns the currently-applied config for [matchId], or
     * [MatchConfig.DEFAULT] when no overlay has been pushed yet.
     */
    public fun configOf(matchId: MatchId): MatchConfig

    /**
     * Apply [config] to [matchId]'s world. Replaces any previously-applied
     * overlay for the same match. The caller is responsible for supplying
     * the [worldId] of the match (the service intentionally does not look
     * it up — match-world resolution lives in the match plugin's wiring).
     */
    public fun apply(matchId: MatchId, worldId: WorldId, config: MatchConfig): Result<Unit, MatchConfigError>

    /**
     * Lift the overlay for [matchId], restoring the world rules to the
     * snapshot captured at the most recent [apply] call. No-op when no
     * overlay is active for the match.
     *
     * Returns the previous config that was lifted, or [MatchConfig.DEFAULT]
     * when nothing was active.
     */
    public fun resetToDefault(matchId: MatchId): MatchConfig
}

/**
 * Failure modes for [IMatchConfigService.apply].
 *
 * Wave 5n (ADR-0080).
 */
public sealed class MatchConfigError {
    public abstract val messageKey: MessageKey

    /** The match has no associated world (matchId unknown to the match plugin). */
    public data class UnknownMatch(val matchId: MatchId) : MatchConfigError() {
        override val messageKey: MessageKey = MessageKey("astera.match.config.unknown_match")
    }

    /** The world referenced by the config is unknown to the world gateways. */
    public data class UnknownWorld(val worldId: WorldId) : MatchConfigError() {
        override val messageKey: MessageKey = MessageKey("astera.match.config.unknown_world")
    }
}
