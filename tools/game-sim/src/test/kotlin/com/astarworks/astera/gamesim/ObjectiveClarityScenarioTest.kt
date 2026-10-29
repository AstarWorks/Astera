package com.astarworks.astera.gamesim

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MODE-21: every player is told what to do. When a match turns ACTIVE the objective is flashed as a title and written
 * out in chat; a player on their first match also gets the beginner guide; two minutes into live play the objective is
 * repeated as a reminder.
 */
class ObjectiveClarityScenarioTest {

    private fun ScenarioScope.chat(who: SimEntity): String = checkNotNull(sim.server.view(who.id)).messages.joinToString("\n")

    @Test
    fun `carry mini tnt - title and chat on start, first-match guide, then a reminder two minutes in`() {
        scenario("objective-clarity-mini-tnt") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("carry-mini-tnt", mapOf(red to "red", blue to "blue"))
            advance(260)

            val titles = checkNotNull(sim.server.view(red.id)).titles.map { it.value }
            assertThat(titles).`as`("the objective is flashed as a title").contains("astera.match.objective.carry-mini-tnt.title")
            val start = chat(red)
            assertThat(start).contains("Rules").contains("ENEMY basket")
            assertThat(start).`as`("a player who never finished a match gets the beginner guide").contains("Quick guide")
            assertThat(start).doesNotContain("Reminder - how to win")

            advance(20 * 125)
            assertThat(chat(red)).`as`("the objective is repeated after two minutes").contains("Reminder - how to win")
            assertThat(chat(blue)).contains("Reminder - how to win")
        }
    }
}
