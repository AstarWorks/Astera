package com.astarworks.astera.core.domain.moderation

/**
 * Scope of a [ModerationAction.Ban].
 *
 * - [Global] — the player is denied login on every Astera server.
 * - [PerServer] — the player is denied login only on the named server
 *   (`serverId` matches the Paper / proxy identifier).
 *
 * Carried by [ModerationAction.Ban.scope] so per-server bans are
 * distinguishable from global bans without overloading [BanScope.PerServer]'s
 * domain type. Wave 5h records the scope; Wave 6b's `PlayerLoginEvent`
 * listener acts on it.
 */
public sealed interface BanScope {
    public data object Global : BanScope
    public data class PerServer(public val serverId: String) : BanScope {
        init { require(serverId.isNotBlank()) { "serverId must not be blank" } }
    }
}
