package com.astarworks.astera.core.application.port.outbound.admin

import com.astarworks.astera.core.domain.player.PlayerId

/** Minimal vendor-neutral game mode vocabulary for the admin panel. */
public enum class AdminGameMode { SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR }

/** One row of the admin panel's online-player list. */
public data class AdminOnlinePlayer(public val id: PlayerId, public val name: String)

/**
 * Outbound port: the host-level player operations the `/astera admin panel` panel
 * needs that no other port covers (F1-D, ADR-0183).
 *
 * Everything here is "server admin" plumbing — the Paper impl maps it onto
 * `Player#setGameMode`, `Player#setAllowFlight`, `Player#kick`, … The
 * headless simulator and tests use [com.astarworks.astera.core.application.service.admin.InMemoryAdminPlayerGateway].
 * All calls run on the server main thread (they are invoked from GUI clicks).
 */
public interface IAdminPlayerGateway {

    /** Whether the host considers [player] a server operator (Paper `op`). Ops pass the panel permission gate. */
    public fun isOperator(player: PlayerId): Boolean

    /** Everyone currently online, stable order (by name, case-insensitive). */
    public fun onlinePlayers(): List<AdminOnlinePlayer>

    /** Teleport [who] next to [destination]. `false` if either is offline. */
    public fun teleportToPlayer(who: PlayerId, destination: PlayerId): java.util.concurrent.CompletableFuture<Boolean>

    /**
     * Flip [player] between creative and survival (anything else — adventure /
     * spectator — goes to survival). Returns the new mode, `null` if offline.
     */
    public fun toggleGameMode(player: PlayerId): AdminGameMode?

    /** Flip flight permission + state. Returns the new state, `null` if offline. */
    public fun toggleFlight(player: PlayerId): Boolean?

    /** Disconnect [player] with [reason]. `false` if offline. */
    public fun kick(player: PlayerId, reason: String): Boolean
}
