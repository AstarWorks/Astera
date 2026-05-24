package com.astarworks.astera.match.application.service

import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Default in-memory [IGameModeFrameworkRegistry] (ADR-0106, Wave 5gamemode).
 *
 * Not thread-safe — population happens at plugin `onEnable` and lookups happen
 * on the main server thread, so the single-threaded contract holds (mirrors
 * [InMemoryGameModeRegistry], the legacy config-side registry).
 *
 * The Persistence plugin MAY override with a higher-priority impl in later
 * phases if mode discovery needs to round-trip through the DB.
 */
public class InMemoryGameModeFrameworkRegistry : IGameModeFrameworkRegistry {

    private val store: MutableMap<GameModeId, IGameMode> = mutableMapOf()

    override fun register(mode: IGameMode) {
        store[mode.id()] = mode
    }

    override fun get(id: GameModeId): IGameMode? = store[id]

    override fun all(): List<IGameMode> = store.values.toList()
}
