package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * What decides a match must be answerable from what the player is shown: a briefing when the match goes ACTIVE (the
 * mode's objective + what Norma levels are for), and a result screen that names the winning objective and each team's
 * final Norma standing. Norma levels are power progression, never a win condition (ADR-0288).
 */
class NormaExplainedScenarioTest {

    private fun ScenarioScope.chat(who: SimEntity): String = checkNotNull(sim.server.view(who.id)).messages.joinToString("\n")

    private fun ScenarioScope.climb(matchId: MatchId, who: SimEntity, levels: Int) {
        val stars = sim.services.getOrThrow<IMatchStarStore>()
        repeat(levels) {
            stars.add(matchId, who.id, 25)
            sim.eventBus.publish(StarsCollected(matchId, who.id, 25, stars.get(matchId, who.id)))
            advance(2)
        }
    }

    @Test
    fun `carry-tnt - the briefing teaches the objective and the Norma unlocks, the result names the delivery and both Norma standings`() {
        scenario("norma-explained-carry-tnt") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")
            advance(40)

            val briefing = chat(red)
            assertThat(briefing).contains("How to win").contains("carry it into the enemy base").contains("unlock").contains("Time limit 20 min")
            assertThat(chat(blue)).`as`("everybody is briefed").contains("How to win")

            val sidebar = sim.hud.sidebarText(red.id).joinToString("\n")
            assertThat(sidebar).`as`("the clock shows the limit (the sim shows the sidebar line keys with their placeholders)").contains("limit=20:00")
            assertThat(sidebar).`as`("the own Norma level is on the sidebar").contains("level=1,max=5")

            repeat(2) {
                red.moveTo(Vec3(0.0, 65.0, -6.0))
                advance(40)
                red.moveTo(Vec3(43.0, 65.0, 0.0))
                advance(40)
            }

            eventsOf<MatchEnded>().single()
            val loserChat = chat(blue)
            assertThat(loserChat).`as`("the verdict says what decided it, not 'objective completion'").contains("delivered the TNT to the enemy base")
            assertThat(loserChat).`as`("both teams' final Norma level is on the result screen").contains("Norma").contains("Lv 1/5")
            assertThat(loserChat).`as`("no 'reach level N to win' hint exists any more").doesNotContain("To win:")
        }
    }

    @Test
    fun `reaching the top Norma level does not end the match`() {
        scenario("norma-explained-top-level") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            climb(matchId, red, levels = 4)

            advance(40)
            assertThat(eventsOf<MatchEnded>()).`as`("only the mode's own objective ends a match").isEmpty()
            assertThat(chat(blue)).doesNotContain("and won")
        }
    }

    @Test
    fun `every shipped game mode has a briefing line in both languages, and every objective mode names its win`() {
        val root = GameSim.defaultContentDir()
        val modes = Files.list(root.resolve("game-modes")).use { s -> s.map { it.fileName.toString().removeSuffix(".yaml") }.toList() }
        val objectiveModes = modes - setOf("pve-waves")
        for (lang in listOf("en", "ja")) {
            val text = Files.readString(root.resolve("languages/$lang.yaml"))
            val briefing = text.substringAfter("    briefing:\n").substringBefore("      norma-rank:")
            for (mode in modes) assertThat(briefing).`as`("$lang briefing for $mode").contains("        $mode: ")
            val reasons = text.substringAfter("        objective-by:\n").substringBefore("    # F3-4")
            for (mode in objectiveModes) assertThat(reasons).`as`("$lang win reason for $mode").contains("          $mode: ")
        }
    }
}
