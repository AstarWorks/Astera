package com.astarworks.astera.match.domain.gamemode

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.mode.GameModeId
import java.time.Instant

/**
 * Framework-level events published when an [IGameMode] crosses a lifecycle
 * boundary (ADR-0106, Wave 5gamemode).
 *
 * Distinct from the existing `MatchStarted` / `MatchEnded` / `PhaseChanged`
 * variants in `core.domain.event.*` — those describe what the *match state
 * machine* did. These describe what the *mode framework* observed and
 * actioned on top, which downstream consumers (HUD, audit, queue) may want
 * to subscribe to without re-deriving "did the framework think this was a
 * phase change" from the raw match events.
 *
 * For Phase 1 PoC the framework publishes these as it processes the
 * MatchStarted / PhaseChanged / MatchEnded match events; downstream
 * consumers may subscribe to either layer. Phase 3 (Siege) is expected to
 * add custom phases that don't have a Core `MatchPhase` analog and will
 * publish only the framework variant.
 *
 * All variants implement [DomainEvent] so the central [`IDomainEventBus`]
 * can dispatch them like any other domain event.
 */
public sealed interface GameModeEvent : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(com.astarworks.astera.core.domain.game.InstanceId(matchId.value))


    /** The match this event is bound to. Mirrors the canonical `matchId` shape. */
    public val matchId: MatchId

    /** The mode that produced the event. Resolves to the registry key. */
    public val modeId: GameModeId

    /** Server tick the event was published at. Mirrors `core.domain.event.*` convention. */
    public val atTick: Long

    /**
     * The framework observed `MatchStarted` for [matchId] and dispatched
     * `IGameMode.onMatchStarted`.
     */
    public data class GameModeStarted(
        override val matchId: MatchId,
        override val modeId: GameModeId,
        override val atTick: Long,
        override val version: Int = 1,
        override val occurredAt: Instant = Instant.now(),
    ) : GameModeEvent

    /**
     * The framework observed a phase transition and dispatched
     * `IGameMode.onPhaseChanged`. Carries both phases so consumers can
     * distinguish "entered SuddenDeath" from "left Active".
     */
    public data class GameModePhaseChanged(
        override val matchId: MatchId,
        override val modeId: GameModeId,
        val oldPhase: IGameModePhase,
        val newPhase: IGameModePhase,
        override val atTick: Long,
        override val version: Int = 1,
        override val occurredAt: Instant = Instant.now(),
    ) : GameModeEvent

    /**
     * The framework observed `MatchEnded` for [matchId] and dispatched
     * `IGameMode.onMatchEnded`. Fires after `MatchEnded` itself; consumers
     * that need both should subscribe to this variant alone (it implies
     * MatchEnded happened first).
     */
    public data class GameModeEnded(
        override val matchId: MatchId,
        override val modeId: GameModeId,
        override val atTick: Long,
        override val version: Int = 1,
        override val occurredAt: Instant = Instant.now(),
    ) : GameModeEvent
}
