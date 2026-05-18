package com.astarworks.astera.match.application.service

import com.astarworks.astera.match.application.port.outbound.IGameModeRegistry
import com.astarworks.astera.match.domain.mode.GameMode
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Default in-memory [IGameModeRegistry]. Not thread-safe — population happens
 * at plugin `onEnable` and lookups happen on the main server thread, so the
 * single-threaded contract holds. The Persistence plugin may override with a
 * higher-priority impl in later phases.
 */
public class InMemoryGameModeRegistry : IGameModeRegistry {

    private val store: MutableMap<GameModeId, GameMode> = mutableMapOf()

    override fun register(mode: GameMode) {
        store[mode.id] = mode
    }

    override fun get(id: GameModeId): GameMode? = store[id]

    override fun all(): List<GameMode> = store.values.toList()
}
