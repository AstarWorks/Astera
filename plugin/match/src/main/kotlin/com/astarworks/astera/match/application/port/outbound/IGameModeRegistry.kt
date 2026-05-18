package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.match.domain.mode.GameMode
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Outbound port: in-process registry of [GameMode] instances available to
 * matches.
 *
 * Resolved via Core's `ServiceRegistry`. The default in-memory implementation
 * ([com.astarworks.astera.match.application.service.InMemoryGameModeRegistry])
 * is registered by `AsteraMatchPlugin.onEnable`; downstream plugins or YAML
 * loaders populate it with concrete modes (CarryTNT, CaptureWool, StealJewelry,
 * BattleLoyal, …) before matches start.
 *
 * Single-threaded by contract — population happens at `onEnable`, lookups
 * happen on the main server thread.
 */
public interface IGameModeRegistry {

    /** Registers [mode] keyed by its [GameMode.id]. Last-write-wins on conflict. */
    public fun register(mode: GameMode)

    /** Returns the registered mode for [id] or null if none has been registered. */
    public fun get(id: GameModeId): GameMode?

    /** Returns every currently-registered mode. Order is implementation-defined. */
    public fun all(): List<GameMode>
}
