package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.adapter.api.hud.IScoreboard
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ITeamScoreboard
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.adapter.api.hud.ScoreboardLine
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IScoreboardManager] for use case tests.
 *
 * Stores both flavors of board in a shared id-keyed registry plus a
 * per-player "what are they viewing right now?" map. Modeled after
 * the other HUD fakes.
 *
 * **Single active board per player.** [show] swaps the player's current
 * view; [currentScoreboard] / [currentTeamScoreboard] discriminate on
 * flavor so callers can branch without `instanceof` checks.
 *
 * Thread-agnostic — domain/application tests don't simulate the Bukkit
 * main-thread requirement.
 */
public class FakeScoreboardManager : IScoreboardManager {

    private val lineBoards: MutableMap<ScoreboardId, FakeScoreboard> = linkedMapOf()
    private val teamBoards: MutableMap<ScoreboardId, FakeTeamScoreboard> = linkedMapOf()

    /**
     * Sealed union for tracking which flavor of board a viewer is bound to.
     * Not exposed publicly — callers go through [currentScoreboard] /
     * [currentTeamScoreboard].
     */
    private sealed interface ActiveBoard {
        val id: ScoreboardId
        data class Line(val board: FakeScoreboard) : ActiveBoard {
            override val id: ScoreboardId get() = board.id
        }
        data class Team(val board: FakeTeamScoreboard) : ActiveBoard {
            override val id: ScoreboardId get() = board.id
        }
    }

    private val viewers: MutableMap<PlayerId, ActiveBoard> = linkedMapOf()

    override fun createScoreboard(id: ScoreboardId, title: MessageKey): IScoreboard {
        check(id !in teamBoards) {
            "ScoreboardId '$id' is already registered as a team-style board."
        }
        val existing = lineBoards[id]
        if (existing != null) {
            existing.setTitle(title)
            return existing
        }
        val created = FakeScoreboard(id, title)
        lineBoards[id] = created
        return created
    }

    override fun createTeamScoreboard(id: ScoreboardId, title: MessageKey): ITeamScoreboard {
        check(id !in lineBoards) {
            "ScoreboardId '$id' is already registered as a line-style board."
        }
        val existing = teamBoards[id]
        if (existing != null) {
            existing.setTitle(title)
            return existing
        }
        val created = FakeTeamScoreboard(id, title)
        teamBoards[id] = created
        return created
    }

    override fun scoreboard(id: ScoreboardId): IScoreboard? = lineBoards[id]

    override fun teamScoreboard(id: ScoreboardId): ITeamScoreboard? = teamBoards[id]

    override fun remove(id: ScoreboardId) {
        val removedLine = lineBoards.remove(id)
        val removedTeam = teamBoards.remove(id)
        if (removedLine == null && removedTeam == null) return
        // Hide from any viewer currently bound to this id.
        val iter = viewers.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.id == id) iter.remove()
        }
    }

    override fun show(playerId: PlayerId, scoreboard: IScoreboard) {
        viewers[playerId] = ActiveBoard.Line(scoreboard as FakeScoreboard)
    }

    override fun show(playerId: PlayerId, scoreboard: ITeamScoreboard) {
        viewers[playerId] = ActiveBoard.Team(scoreboard as FakeTeamScoreboard)
    }

    override fun hide(playerId: PlayerId) {
        viewers.remove(playerId)
    }

    override fun currentScoreboard(playerId: PlayerId): IScoreboard? =
        (viewers[playerId] as? ActiveBoard.Line)?.board

    override fun currentTeamScoreboard(playerId: PlayerId): ITeamScoreboard? =
        (viewers[playerId] as? ActiveBoard.Team)?.board

    /**
     * In-memory [IScoreboard]. Lines are stored in a `MutableMap<Int, ScoreboardLine>`
     * keyed by index for O(1) replace/remove and sorted on read.
     */
    public class FakeScoreboard internal constructor(
        override val id: ScoreboardId,
        initialTitle: MessageKey,
    ) : IScoreboard {

        private var titleBacking: MessageKey = initialTitle
        override val title: MessageKey
            get() = titleBacking

        private val lineMap: MutableMap<Int, ScoreboardLine> = linkedMapOf()

        override fun setTitle(title: MessageKey) {
            titleBacking = title
        }

        override fun addLine(index: Int, text: MessageKey, placeholders: Map<String, String>) {
            require(index in 0..14) {
                "Scoreboard line index must be in 0..14 (Minecraft sidebar limit): $index"
            }
            lineMap[index] = ScoreboardLine(index, renderTemplate(text, placeholders))
        }

        override fun removeLine(index: Int) {
            lineMap.remove(index)
        }

        override fun lines(): List<ScoreboardLine> =
            lineMap.values.sortedBy { it.index }

        /**
         * Naive template renderer.
         *
         * Treats [key]'s key-string as the literal template and substitutes
         * each `{name}` occurrence with `placeholders[name]`. Unknown
         * placeholders are left as `{name}` literals. Used only by the
         * fake — Wave 6's Paper impl will replace this with MiniMessage +
         * `IMessageRenderer` lookup so each viewer sees their locale.
         *
         * Note: [MessageKey]'s regex disallows `{` / `}`, so production
         * key-strings cannot contain placeholder tokens directly. The
         * fake's contract is "if a key happens to embed `{name}`, it gets
         * substituted" — useful only for the test that asserts the
         * substitution mechanic works.
         */
        private fun renderTemplate(key: MessageKey, placeholders: Map<String, String>): String {
            var out = key.value
            for ((name, value) in placeholders) {
                out = out.replace("{$name}", value)
            }
            // Append placeholders in a stable serialized form so tests can
            // assert "placeholders were passed through" even when the
            // key-string contains no `{name}` tokens.
            if (placeholders.isNotEmpty()) {
                val serialized = placeholders.toSortedMap().entries
                    .joinToString(",") { "${it.key}=${it.value}" }
                out = "$out [$serialized]"
            }
            return out
        }
    }

    /**
     * In-memory [ITeamScoreboard]. Display names and scores live in
     * parallel maps keyed by team id; [setScore] enforces prior registration.
     */
    public class FakeTeamScoreboard internal constructor(
        override val id: ScoreboardId,
        initialTitle: MessageKey,
    ) : ITeamScoreboard {

        private var titleBacking: MessageKey = initialTitle
        private val displayNames: MutableMap<String, MessageKey> = linkedMapOf()
        private val scores: MutableMap<String, Int> = linkedMapOf()

        /** Current title, exposed for test readability. */
        public val title: MessageKey
            get() = titleBacking

        override fun setTitle(title: MessageKey) {
            titleBacking = title
        }

        override fun addTeam(teamId: String, displayName: MessageKey) {
            displayNames[teamId] = displayName
            // Preserve existing score on re-registration.
            scores.putIfAbsent(teamId, 0)
        }

        override fun removeTeam(teamId: String) {
            displayNames.remove(teamId)
            scores.remove(teamId)
        }

        override fun setScore(teamId: String, score: Int) {
            check(teamId in displayNames) {
                "Team '$teamId' is not registered on scoreboard '$id'. Call addTeam(...) first."
            }
            scores[teamId] = score
        }

        override fun currentScore(teamId: String): Int? =
            if (teamId in displayNames) scores[teamId] else null

        override fun teams(): Map<String, Int> =
            displayNames.keys.associateWith { scores[it] ?: 0 }
    }
}
