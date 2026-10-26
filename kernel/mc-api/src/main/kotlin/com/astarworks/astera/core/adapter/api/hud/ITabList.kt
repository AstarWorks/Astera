package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Single row in a player's private tablist.
 *
 * - [name] is the player-visible identifier (typically a Minecraft player
 *   name, but the port does not assume anything beyond "non-blank string").
 *   The Wave 6 Paper impl (ADR-0031) maps this to the corresponding
 *   `TabListEntry` / `PlayerInfoUpdate` packet field.
 * - [displayName] is an optional i18n key. `null` means "use [name] as-is".
 *   When non-null, the renderer resolves it per the viewer's locale.
 *   MiniMessage-formatting is the renderer's job; this port only carries
 *   the key.
 * - [pingMs] is the latency badge in milliseconds. Must be non-negative;
 *   `0` is a legitimate value (loopback). The Paper impl translates this
 *   into the standard 5-bar ping icon.
 */
public data class TabListEntry(
    public val name: String,
    public val displayName: MessageKey?,
    public val pingMs: Int = 0,
) {
    init {
        require(pingMs >= 0) {
            "TabListEntry.pingMs must be non-negative: $pingMs"
        }
    }
}

/**
 * Vendor-neutral handle for each player's tablist (header / footer / rows).
 *
 * Per ADR-0001/0002 this lives in `core.adapter.api` and stays free of
 * Bukkit / Adventure imports. The Paper/Folia implementation (ADR-0260)
 * routes header, footer and rows through [PacketSink]; the
 * in-memory [com.astarworks.astera.testfixtures.FakeTabList] covers
 * application-layer tests today.
 *
 * **Per-player isolation.** Each player's tablist is private — setting
 * one player's header / footer / entries does **not** affect any other
 * player. Callers must call the setters per-viewer; there is no
 * "broadcast to everyone" shortcut here. This matches Minecraft's
 * Player List packet model, where each connected client maintains its
 * own tablist state.
 *
 * **Rendering.** [setHeader] / [setFooter] take a [MessageKey] +
 * placeholders pair so i18n + per-locale rendering happens at draw
 * time. The Wave 6 Paper impl resolves through MiniMessage and
 * `IMessageRenderer`. The in-memory fake performs naive `{name}`-style
 * substitution into the key's raw string and returns that as the
 * rendered text (same convention as the scoreboard fake) — sufficient
 * for application tests, not a translation engine.
 *
 * **Replace-not-append.** [setPlayerEntries] replaces the entire row
 * list; there is no incremental "add one row" API. This keeps the
 * mental model identical to a packet rewrite and avoids the ordering
 * ambiguity an incremental API would invite.
 *
 * **Snapshot reads.** [currentEntries] returns an immutable snapshot;
 * mutating the returned list cannot leak back into the port's state.
 * For an unknown player, the methods return `null` (header / footer)
 * or `emptyList()` (entries) — never throw.
 */
public interface ITabList {

    /**
     * Set [playerId]'s tablist header to [header] resolved with
     * [placeholders]. Overwrites any previous header for this player.
     * Other players are unaffected.
     */
    public fun setHeader(
        playerId: PlayerId,
        header: MessageKey,
        placeholders: Map<String, String> = emptyMap(),
    )

    /**
     * Set [playerId]'s tablist footer to [footer] resolved with
     * [placeholders]. Overwrites any previous footer for this player.
     * Other players are unaffected.
     */
    public fun setFooter(
        playerId: PlayerId,
        footer: MessageKey,
        placeholders: Map<String, String> = emptyMap(),
    )

    /**
     * Drop [playerId]'s header. After this, [currentHeader] returns
     * `null` for [playerId]. No-op if no header was set.
     */
    public fun clearHeader(playerId: PlayerId)

    /**
     * Drop [playerId]'s footer. After this, [currentFooter] returns
     * `null` for [playerId]. No-op if no footer was set.
     */
    public fun clearFooter(playerId: PlayerId)

    /**
     * Replace the row list [playerId] sees in their tablist with
     * [entries], in order. Other players' tablists are unaffected.
     *
     * Calling this again replaces the previous list entirely — this is
     * not an append API.
     */
    public fun setPlayerEntries(playerId: PlayerId, entries: List<TabListEntry>)

    /**
     * Currently-rendered header text for [playerId], with placeholders
     * already interpolated. `null` if no header is set for this player.
     */
    public fun currentHeader(playerId: PlayerId): String?

    /**
     * Currently-rendered footer text for [playerId], with placeholders
     * already interpolated. `null` if no footer is set for this player.
     */
    public fun currentFooter(playerId: PlayerId): String?

    /**
     * Immutable snapshot of the rows currently shown to [playerId], in
     * insertion order. Empty list if [setPlayerEntries] was never called
     * (or was last called with an empty list); never `null`.
     */
    public fun currentEntries(playerId: PlayerId): List<TabListEntry>
}
