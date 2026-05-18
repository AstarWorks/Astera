package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScoreboardManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FakeScoreboardTest {

    private val player1 = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val player2 = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    private val sidebarId = ScoreboardId("match.sidebar")
    private val teamId = ScoreboardId("match.team-scores")

    private val sidebarTitle = MessageKey("hud.test.scoreboard.title")
    private val teamTitle = MessageKey("hud.test.scoreboard.team-title")

    @Test
    fun `createScoreboard returns a board with title set`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        assertThat(board.id).isEqualTo(sidebarId)
        assertThat(board.title).isEqualTo(sidebarTitle)
        assertThat(board.lines()).isEmpty()
    }

    @Test
    fun `addLine inserts at index in sorted order`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        board.addLine(2, MessageKey("hud.test.line.two"))
        board.addLine(0, MessageKey("hud.test.line.zero"))
        board.addLine(5, MessageKey("hud.test.line.five"))

        val lines = board.lines()
        assertThat(lines).hasSize(3)
        assertThat(lines.map { it.index }).containsExactly(0, 2, 5)
    }

    @Test
    fun `addLine with an existing index replaces the line`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        board.addLine(3, MessageKey("hud.test.line.original"))
        board.addLine(3, MessageKey("hud.test.line.replaced"))

        val lines = board.lines()
        assertThat(lines).hasSize(1)
        assertThat(lines.single().index).isEqualTo(3)
        assertThat(lines.single().rendered).isEqualTo("hud.test.line.replaced")
    }

    @Test
    fun `removeLine deletes the line and is no-op on absent index`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        board.addLine(1, MessageKey("hud.test.line.one"))
        board.removeLine(1)
        assertThat(board.lines()).isEmpty()

        // No-op when nothing is registered at the index.
        board.removeLine(14)
        assertThat(board.lines()).isEmpty()
    }

    @Test
    fun `addLine rejects index outside 0 to 14`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        assertThatThrownBy { board.addLine(-1, MessageKey("hud.test.bad")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { board.addLine(15, MessageKey("hud.test.bad")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `placeholders are substituted into the rendered line`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        // MessageKey's regex disallows `{` / `}` so we can't embed the
        // template tokens directly inside a key-string. The fake's contract
        // is "placeholders are also serialized into the rendered output in a
        // stable form" so the test can still assert the values flowed through.
        board.addLine(
            index = 0,
            text = MessageKey("hud.test.score.kills"),
            placeholders = mapOf("count" to "5", "player" to "alice"),
        )

        val rendered = board.lines().single().rendered
        assertThat(rendered).contains("hud.test.score.kills")
        assertThat(rendered).contains("count=5")
        assertThat(rendered).contains("player=alice")
    }

    @Test
    fun `ITeamScoreboard addTeam then setScore reflects in currentScore`() {
        val manager = FakeScoreboardManager()
        val board = manager.createTeamScoreboard(teamId, teamTitle)

        board.addTeam("red", MessageKey("hud.test.team.red"))
        board.setScore("red", 7)

        assertThat(board.currentScore("red")).isEqualTo(7)
        assertThat(board.teams()).containsEntry("red", 7)
    }

    @Test
    fun `setScore for unregistered team throws IllegalStateException`() {
        val manager = FakeScoreboardManager()
        val board = manager.createTeamScoreboard(teamId, teamTitle)

        assertThatThrownBy { board.setScore("blue", 3) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Team 'blue' is not registered")
            .hasMessageContaining(teamId.value)
            .hasMessageContaining("addTeam")
    }

    @Test
    fun `removeTeam removes the score and currentScore returns null`() {
        val manager = FakeScoreboardManager()
        val board = manager.createTeamScoreboard(teamId, teamTitle)

        board.addTeam("red", MessageKey("hud.test.team.red"))
        board.setScore("red", 10)
        board.removeTeam("red")

        assertThat(board.currentScore("red")).isNull()
        assertThat(board.teams()).doesNotContainKey("red")
    }

    @Test
    fun `show makes the scoreboard visible to the player`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        manager.show(player1, board)

        assertThat(manager.currentScoreboard(player1)).isSameAs(board)
        assertThat(manager.currentScoreboard(player2)).isNull()
    }

    @Test
    fun `show twice on same player swaps the active scoreboard`() {
        val manager = FakeScoreboardManager()
        val first = manager.createScoreboard(ScoreboardId("first"), MessageKey("hud.test.first"))
        val second = manager.createScoreboard(ScoreboardId("second"), MessageKey("hud.test.second"))

        manager.show(player1, first)
        manager.show(player1, second)

        assertThat(manager.currentScoreboard(player1)).isSameAs(second)
    }

    @Test
    fun `show with ITeamScoreboard then currentScoreboard returns null and currentTeamScoreboard returns the team board`() {
        val manager = FakeScoreboardManager()
        val board = manager.createTeamScoreboard(teamId, teamTitle)

        manager.show(player1, board)

        assertThat(manager.currentScoreboard(player1)).isNull()
        assertThat(manager.currentTeamScoreboard(player1)).isSameAs(board)
    }

    @Test
    fun `hide clears the player's current scoreboard`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        manager.show(player1, board)
        manager.hide(player1)

        assertThat(manager.currentScoreboard(player1)).isNull()
        assertThat(manager.currentTeamScoreboard(player1)).isNull()
    }

    @Test
    fun `remove(id) hides from all viewers`() {
        val manager = FakeScoreboardManager()
        val board = manager.createScoreboard(sidebarId, sidebarTitle)

        manager.show(player1, board)
        manager.show(player2, board)

        manager.remove(sidebarId)

        assertThat(manager.scoreboard(sidebarId)).isNull()
        assertThat(manager.currentScoreboard(player1)).isNull()
        assertThat(manager.currentScoreboard(player2)).isNull()
    }

    @Test
    fun `ScoreboardLine rejects index outside 0 to 14 in init`() {
        assertThatThrownBy { ScoreboardLine(-1, "x") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ScoreboardLine(15, "x") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
