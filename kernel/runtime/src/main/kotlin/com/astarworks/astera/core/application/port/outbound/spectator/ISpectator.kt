package com.astarworks.astera.core.application.port.outbound.spectator

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: switch a player into / out of spectator mode and manage the
 * third-person camera target (Wave 6c-§D, ADR-0160; deferred from Wave 6b-2 /
 * ADR-0157 §F).
 *
 * # Why this is a port
 *
 * Spectator gameplay in Astera is a **match-time UX** concern (eliminated
 * players free-look the remaining combatants; quest authors put players into
 * spectator for cinematic sequences). The mechanism is a Bukkit
 * `GameMode.SPECTATOR` swap + optional `Player.setSpectatorTarget(target)`,
 * but use cases must not import `org.bukkit.*` (Konsist rule #1). This port
 * is the vendor-neutral surface use cases call; the Paper adapter
 * (`com.astarworks.astera.match.adapter.paper.spectator.PaperSpectator`)
 * translates to Bukkit on main thread.
 *
 * # Distinction from `EnterSpectatorUseCase`
 *
 * [com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase]
 * (Wave 5e) flips the **domain** `SessionState.Spectator` on a tracked
 * `PlayerSession` and optionally calls `IWorldGateway.spectatorSpawn` for
 * the teleport. **This port is the orthogonal Bukkit-gamemode swap** —
 * domain state and Bukkit-state are independent in Phase 1 (a player can
 * be `SessionState.Spectator` without their Bukkit GameMode actually
 * being SPECTATOR if no adapter wired this port). Use cases that need the
 * gamemode swap (real third-person camera + flight + non-collidable) call
 * `ISpectator.startSpectating(...)` **after** the session-state flip.
 *
 * The split exists because:
 *  - Some quest-mode flows want the domain Spectator state without the
 *    Bukkit camera mode (e.g. NPC-watch UX renders via packet-only).
 *  - Tests of `EnterSpectatorUseCase` must run without Paper, so the
 *    use case cannot directly call `Player.setGameMode`.
 *
 * # Camera target semantics
 *
 * [setCameraTarget] attaches `viewer`'s third-person camera to `target`
 * (Bukkit `Player.setSpectatorTarget(target)`). Vanilla rules apply:
 *  - viewer must already be in SPECTATOR gamemode (else returns
 *    [SpectatorError.Unsupported] with reason `"viewer-not-spectating"`).
 *  - target must be online (else [SpectatorError.TargetNotOnline]).
 *  - target can be any LivingEntity in vanilla; Phase 1 PoC restricts to
 *    online players (matches Astera's spectator-of-player UX). Other
 *    entity types are a Phase 2 extension.
 *
 * # Stop semantics
 *
 * [stopSpectating] resets the viewer to **`GameMode.SURVIVAL`** by default
 * (Phase 1 PoC convention — survival is the lobby / match default). If a
 * caller needs ADVENTURE / CREATIVE on exit, the caller is responsible
 * for the second gamemode swap **after** `stopSpectating` returns. A
 * dedicated `IGameModeGateway` is a Phase 2 concern; until then,
 * `stopSpectating` is the only port surface for leaving spectator.
 *
 * # Threading
 *
 * Bukkit gamemode + spectator-target mutations require the server main
 * thread. The Paper adapter round-trips off-main calls through
 * [IScheduler.run] with [kotlin.time.Duration.ZERO]; the InMemory
 * Fake has no threading constraint and accepts any thread.
 *
 * # Wire-up
 *
 * `AsteraMatchPlugin.onEnable` registers `PaperSpectator` at priority 100
 * (overlays the Fake at priority 0 in non-Paper test harnesses).
 *
 * @see com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase
 *     for the domain-state transition that typically precedes
 *     [startSpectating].
 */
public interface ISpectator {

    /**
     * Switch [viewer] into Bukkit `GameMode.SPECTATOR`.
     *
     * Idempotent — calling on an already-spectating viewer returns
     * [Result.Success] without re-applying the gamemode. The InMemory Fake
     * records the call once; the Paper adapter no-ops the second call.
     *
     * Fails with:
     *  - [SpectatorError.ViewerNotOnline] when the viewer is offline.
     *  - [SpectatorError.Unsupported] when running on a backend that
     *    rejects gamemode changes (e.g. immutable-gamemode test harness).
     */
    public fun startSpectating(viewer: PlayerId): Result<Unit, SpectatorError>

    /**
     * Exit spectator mode for [viewer] and reset to `GameMode.SURVIVAL`.
     *
     * The calling use case is responsible for the final gamemode if it
     * differs from SURVIVAL (e.g. ADVENTURE for lobby UX); this port has
     * no `targetGameMode` parameter to keep the surface minimal until an
     * `IGameModeGateway` lands in Phase 2.
     *
     * As a side effect, any active camera target is released (equivalent
     * to [releaseCameraTarget]) before the gamemode swap — vanilla
     * Bukkit drops the spectator-target on gamemode change, so callers
     * see the same observable behaviour on Paper and Fake.
     *
     * Fails with:
     *  - [SpectatorError.ViewerNotOnline] when the viewer is offline.
     *  - [SpectatorError.Unsupported] when the viewer was never tracked
     *    (Fake-only — Paper always succeeds for online players regardless
     *    of prior [startSpectating] state).
     */
    public fun stopSpectating(viewer: PlayerId): Result<Unit, SpectatorError>

    /**
     * Attach [viewer]'s third-person camera to [target] (Bukkit
     * `Player.setSpectatorTarget(target)`).
     *
     * Preconditions: viewer must already be in spectator mode (call
     * [startSpectating] first). Vanilla rules apply: detaching the camera
     * fires implicitly when target dies / disconnects; callers can also
     * call [releaseCameraTarget] explicitly.
     *
     * Fails with:
     *  - [SpectatorError.ViewerNotOnline] / [SpectatorError.TargetNotOnline]
     *    when either is offline.
     *  - [SpectatorError.TargetNotSpectatable] when the target is not a
     *    valid spectator subject (e.g. target is also spectating, or
     *    target is in a world the viewer can't observe).
     *  - [SpectatorError.Unsupported] reason `"viewer-not-spectating"`
     *    when the viewer is not in spectator gamemode.
     */
    public fun setCameraTarget(viewer: PlayerId, target: PlayerId): Result<Unit, SpectatorError>

    /**
     * Detach [viewer]'s third-person camera (Bukkit
     * `Player.setSpectatorTarget(null)`). Idempotent — releasing when no
     * target is attached succeeds.
     *
     * Fails with [SpectatorError.ViewerNotOnline] when the viewer is
     * offline.
     */
    public fun releaseCameraTarget(viewer: PlayerId): Result<Unit, SpectatorError>

    /**
     * Whether [viewer] is currently in spectator mode according to this
     * gateway.
     *
     * Paper adapter: reads Bukkit `Player.getGameMode() == SPECTATOR`.
     * Fake: reads the tracked-spectator set. Returns `false` for offline
     * viewers (the gamemode is not observable while the player is not
     * connected).
     */
    public fun isSpectating(viewer: PlayerId): Boolean
}

/**
 * Expected failure modes of [ISpectator].
 *
 * Each variant carries an i18n [messageKey] so command / event handlers
 * can render a localized message via `IMessageRenderer` without leaking
 * adapter detail. Keys follow the `astera.match.spectator.gateway.error.*`
 * convention (distinct from `astera.match.spectator.error.*` which the
 * Wave 5e use-case-level [com.astarworks.astera.match.application.usecase.spectator.SpectatorError]
 * already owns).
 */
public sealed class SpectatorError {
    public abstract val messageKey: MessageKey

    /** The viewer is not online (cannot apply gamemode / read state). */
    public data class ViewerNotOnline(val playerId: PlayerId) : SpectatorError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spectator.gateway.error.viewer-not-online")
    }

    /** The camera target is not online (cannot attach). */
    public data class TargetNotOnline(val playerId: PlayerId) : SpectatorError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spectator.gateway.error.target-not-online")
    }

    /**
     * The target is online but not a valid spectator subject. [reason] is a
     * short adapter-supplied string (`"target-is-spectator"`, `"different-world"`)
     * suitable for logs but not i18n; callers should surface [messageKey]
     * to players and pass [reason] to telemetry only.
     */
    public data class TargetNotSpectatable(
        val playerId: PlayerId,
        val reason: String,
    ) : SpectatorError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spectator.gateway.error.target-not-spectatable")
    }

    /**
     * Backend rejected the operation. Examples:
     *  - `"viewer-not-spectating"` (camera attach without prior startSpectating)
     *  - `"off-main"` (Paper adapter called off-main with no scheduler)
     *  - `"gamemode-locked"` (server policy disallows manual gamemode swap)
     */
    public data class Unsupported(val reason: String) : SpectatorError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spectator.gateway.error.unsupported")
    }
}
