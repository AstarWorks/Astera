package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Outbound port: registry of behavioural [IGameMode] implementations
 * (ADR-0106, Wave 5gamemode).
 *
 * Distinct from the existing legacy [IGameModeRegistry] which keys the
 * `sealed class GameMode` value carriers (Annihilation, CarryTnt, …) used by
 * per-mode-config listeners. This registry tracks the *behavioural* framework
 * impls (NormaGameMode in Phase 1 PoC, Siege/DM/TDM/CTF/BR in Phase 3+).
 *
 * The two registries coexist by design — see the kdoc on [IGameMode] for
 * the rationale + the eventual merge plan once two non-Norma impls exist
 * to inform the unified shape.
 *
 * Default implementation:
 * [com.astarworks.astera.match.application.service.InMemoryGameModeFrameworkRegistry].
 * The Persistence plugin MAY override at higher priority in a later phase if
 * we ever want mode discovery to round-trip through the DB.
 *
 * Single-threaded contract — population at `AsteraMatchPlugin.onEnable`,
 * lookups on Paper main thread.
 *
 * ## YAML reload integration (Wave 5gamemode §5)
 *
 * The bootstrap impl conforms to the [IReloadableContents] convention so
 * `/astera reload game-modes` routes through the central dispatcher. Each
 * YAML file under `content/game-modes/` is parsed into a `GameModeYamlConfig`
 * that resolves an `implClass` FQN against the JVM class loader; the
 * registered impl singleton survives reload as long as its FQN appears in
 * the new directory snapshot. Removing the YAML for an in-use mode is
 * intentionally non-destructive in Phase 1 — the impl stays registered and
 * a warning is logged; Phase 3 may tighten this.
 */
public interface IGameModeFrameworkRegistry {

    /** Register [mode] keyed by its [IGameMode.id]. Last-write-wins on conflict. */
    public fun register(mode: IGameMode)

    /** Returns the registered mode for [id] or null if none has been registered. */
    public fun get(id: GameModeId): IGameMode?

    /** Returns every currently-registered framework mode. Order is implementation-defined. */
    public fun all(): List<IGameMode>
}
