package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Vendor-neutral scoreboard registry and viewer router.
 *
 * Owns the lifecycle of every [IScoreboard] and [ITeamScoreboard] in the
 * server. Use cases create boards by id, then [show] them to players or
 * [hide] them. The Wave 6 Paper adapter (ADR-0031) implements this against
 * Bukkit's `ScoreboardManager`; the in-memory
 * [com.astarworks.astera.testfixtures.FakeScoreboardManager] implements the
 * same interface for application-layer tests.
 *
 * **One active scoreboard per player.** This is a vanilla Minecraft
 * constraint, not an Astera-imposed one: the protocol exposes a single
 * sidebar slot per client. [show] swaps the current board for the given
 * player; [currentScoreboard] / [currentTeamScoreboard] expose which flavor
 * (if any) is active. Showing a new board automatically hides whatever was
 * previously displayed for that player.
 *
 * **Flavor discrimination.** A single [ScoreboardId] resolves to *one*
 * flavor — line-style or team-style — chosen at creation time by which
 * `create*` method was used. [currentScoreboard] returns `null` if the
 * player's current board is team-style, and vice versa, so callers can
 * branch cleanly.
 *
 * **Id reuse.** [createScoreboard] / [createTeamScoreboard] return the
 * existing instance when [id] is already registered (with the title
 * updated to [title]) so wave-level callers can be idempotent without
 * tracking creation themselves. Creating with one flavor over an
 * existing other-flavor board is a programmer error and the impl is free
 * to fail loud — Wave 5b's fake throws [IllegalStateException].
 */
public interface IScoreboardManager {
    /**
     * Get or create a line-style scoreboard with [id]. If a line-style board
     * already exists under [id] its title is updated to [title] and the
     * existing instance is returned (preserving lines and viewers). Lines
     * are reset on a fresh creation only.
     */
    public fun createScoreboard(id: ScoreboardId, title: MessageKey): IScoreboard

    /**
     * Get or create a team-style scoreboard with [id]. If a team-style
     * board already exists under [id] its title is updated to [title] and
     * the existing instance is returned (preserving teams and scores).
     */
    public fun createTeamScoreboard(id: ScoreboardId, title: MessageKey): ITeamScoreboard

    /** Existing line-style board for [id], or `null` if absent or team-style. */
    public fun scoreboard(id: ScoreboardId): IScoreboard?

    /** Existing team-style board for [id], or `null` if absent or line-style. */
    public fun teamScoreboard(id: ScoreboardId): ITeamScoreboard?

    /**
     * Remove the board registered under [id] regardless of flavor. No-op if
     * absent. The board is also hidden from every player currently viewing
     * it.
     */
    public fun remove(id: ScoreboardId)

    /**
     * Show [scoreboard] to the player with [playerId], replacing whatever
     * board (line- or team-style) they were previously viewing.
     */
    public fun show(playerId: PlayerId, scoreboard: IScoreboard)

    /**
     * Show [scoreboard] (team-style) to the player with [playerId],
     * replacing whatever board they were previously viewing.
     */
    public fun show(playerId: PlayerId, scoreboard: ITeamScoreboard)

    /**
     * Clear any active scoreboard from the player with [playerId]. No-op if
     * no board is currently shown.
     */
    public fun hide(playerId: PlayerId)

    /**
     * Player's currently active line-style scoreboard, or `null` if hidden
     * or if the active board is team-style.
     */
    public fun currentScoreboard(playerId: PlayerId): IScoreboard?

    /**
     * Player's currently active team-style scoreboard, or `null` if hidden
     * or if the active board is line-style.
     */
    public fun currentTeamScoreboard(playerId: PlayerId): ITeamScoreboard?
}
