package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Vendor-neutral handle for a team-style scoreboard.
 *
 * Distinct from [IScoreboard] because the underlying Minecraft shape is
 * different: a team scoreboard exposes a registered set of teams each
 * carrying a single integer score (think team-vs-team match HUD), whereas
 * [IScoreboard] is the free-form 15-line sidebar.
 *
 * **Not a subtype of [IScoreboard].** A single [ScoreboardId] resolves to
 * exactly one flavor; [IScoreboardManager.scoreboard] /
 * [IScoreboardManager.teamScoreboard] act as the discriminator.
 *
 * **Team lifecycle.** [addTeam] registers a team (replacing if the id
 * already exists). [setScore] requires a prior [addTeam] — calling it on
 * an unknown team is a programmer error and throws
 * [IllegalStateException] rather than silently materializing the team,
 * so typos in team ids fail loud.
 *
 * **i18n.** Team display names and the board title are [MessageKey]s
 * resolved per-viewer by the Wave 6 Paper adapter; the Wave 5b fake stores
 * them as-is.
 *
 * **Threading.** Same as [IScoreboard]: Bukkit scoreboard mutation must
 * happen on the main server thread in production. The fake is
 * unsynchronized.
 */
public interface ITeamScoreboard {
    /** Stable identifier the board was created with. */
    public val id: ScoreboardId

    /** Replace the board title. */
    public fun setTitle(title: MessageKey)

    /**
     * Register a team with the given [displayName], or replace its display
     * name if [teamId] is already registered. Re-registering preserves any
     * existing score.
     */
    public fun addTeam(teamId: String, displayName: MessageKey)

    /**
     * Unregister a team. No-op if [teamId] is unknown. Drops both the
     * display name and the score.
     */
    public fun removeTeam(teamId: String)

    /**
     * Set the score for [teamId].
     *
     * @throws IllegalStateException if [teamId] has not been registered via
     *   [addTeam] first. The message names both the team id and this
     *   board's id so the call site is unambiguous.
     */
    public fun setScore(teamId: String, score: Int)

    /** Current score for [teamId], or `null` if the team is not registered. */
    public fun currentScore(teamId: String): Int?

    /**
     * Snapshot of every registered team's score keyed by team id. Immutable
     * — callers cannot mutate the underlying state by reaching through the
     * returned map.
     */
    public fun teams(): Map<String, Int>
}
