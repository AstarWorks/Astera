package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.PlayerTutorialState

/**
 * Outbound port: per-player tutorial completion state (Wave 5h, ADR-0079 §3).
 *
 * Wave 5h ships `InMemoryPlayerTutorialStateRepository` (priority 0) +
 * `PostgresPlayerTutorialStateRepository` (priority 100, V20 migration) +
 * `FakePlayerTutorialStateRepository` (test-fixtures).
 *
 * Drives:
 * - `StartTutorialOnFirstJoinListener` (PlayerJoined → state empty? → start default tutorial)
 * - `SkipTutorialUseCase` / `ReplayTutorialUseCase` / `AdvanceTutorialStepUseCase`
 *
 * Tutorial content itself (steps / messages / triggers) lives in
 * [com.astarworks.astera.core.application.port.outbound.ITutorialRegistry];
 * this port owns only the per-player progress side.
 */
public interface IPlayerTutorialStateRepository {
    /** Returns the player's tutorial state, or a fresh empty state if none persisted. */
    public fun find(playerId: PlayerId): PlayerTutorialState?

    /** Upsert (insert or replace) the player's tutorial state. */
    public fun upsert(state: PlayerTutorialState)

    /** Delete all tutorial state for [playerId] (used on account reset / admin command). */
    public fun delete(playerId: PlayerId)
}
