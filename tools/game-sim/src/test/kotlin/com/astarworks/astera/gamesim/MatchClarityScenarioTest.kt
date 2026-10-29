package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.domain.hud.HudRegion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * MODE-18: at match start every mode tells the player where things are and what to do. Floating labels stand over each
 * kind of region (read differently by the owner and by the rival), every player has a navigation target, the three-line
 * rule card reaches chat, and nothing is left behind when the match ends.
 */
class MatchClarityScenarioTest {

    /** A player in a match is reported to the objective modes by their moves (`PlayerMoved`), as on Paper: report where they stand. */
    private fun ScenarioScope.onTheMove(vararg who: SimEntity) {
        for (e in who) e.moveTo(e.position)
        advance(1)
    }

    private val nowhere = Vec3(0.0, 65.0, -50.0)

    private fun ScenarioScope.chat(who: SimEntity): String = checkNotNull(sim.server.view(who.id)).messages.joinToString("\n")

    private fun ScenarioScope.run(mode: String, stage: String, labelKeys: List<String>, ownerSpecific: String) {
        val red = player("red-1", at = nowhere)
        val blue = player("blue-1", at = nowhere)
        val matchId = startMatch(mode, mapOf(red to "red", blue to "blue"), stage = stage)
        advance(45)
        onTheMove(red, blue)

        val labels = sim.worldLabels.labels(matchId)
        for (key in labelKeys) assertThat(labels.keys).`as`("$mode: label $key").contains(key)
        val redReads = sim.worldLabels.textFor(matchId, ownerSpecific, red.id)
        val blueReads = sim.worldLabels.textFor(matchId, ownerSpecific, blue.id)
        assertThat(redReads).`as`("$mode: $ownerSpecific read by red").isNotBlank()
        assertThat(blueReads).`as`("$mode: $ownerSpecific read by blue").isNotBlank()
        assertThat(redReads).`as`("$mode: the owner and the rival read different text").isNotEqualTo(blueReads)

        // The guide follows the locked navigation compass: it points only while the compass is the held item.
        for (who in listOf(red, blue)) sim.matchCompass.select(who.id, true)
        advance(25)
        for (who in listOf(red, blue)) {
            val guide = sim.hologramGuide.guideOf(who.id)
            assertThat(guide).`as`("$mode: ${who.id} has a navigation target").isNotNull()
            assertThat(guide!!.label).isNotBlank().doesNotStartWith("astera.")
        }

        val card = chat(red)
        assertThat(card).`as`("$mode: rule card in chat").contains("Goal").contains("How").contains("Tip")

        sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.Cancelled("clarity-test"))
        advance(40)
        assertThat(sim.worldLabels.labels(matchId)).`as`("$mode: no label outlives the match").isEmpty()
        assertThat(sim.hologramGuide.liveCount()).isZero()
    }

    @Test
    fun `carry tnt - pedestal and bases are labelled and everyone is pointed somewhere`() {
        scenario("clarity-carry-tnt") { run("carry-tnt", "siege-isles-tnt", listOf("tnt-spawn", "base-red", "base-blue"), "base-blue") }
    }

    @Test
    fun `carry mini tnt - pickup spots and bases are labelled`() {
        scenario("clarity-mini-tnt") { run("carry-mini-tnt", "siege-isles-mini-tnt", listOf("wool-a", "base-red", "base-blue"), "base-blue") }
    }

    @Test
    fun `capture wool - zones are labelled and the nav target is a zone we do not own`() {
        scenario("clarity-capture-wool") { run("capture-wool", "siege-isles-wool", listOf("wool-a", "base-red"), "base-blue") }
    }

    @Test
    fun `steal jewelry - pedestals are labelled per owner`() {
        scenario("clarity-steal-jewelry") { run("steal-jewelry", "siege-isles-jewelry", listOf("jewelry-red", "jewelry-blue"), "jewelry-blue") }
    }

    @Test
    fun `annihilation - every standing pillar is labelled with its tier and HP, and explained`() {
        scenario("clarity-annihilation") {
            val red = player("red-1", at = nowhere)
            val blue = player("blue-1", at = nowhere)
            val yellow = player("yellow-1", at = nowhere)
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            advance(45)
            onTheMove(red, blue, yellow)

            val pillars = sim.worldLabels.labels(matchId).keys.filter { it.startsWith("pillar-") }
            assertThat(pillars).`as`("pillar labels").isNotEmpty()
            // A red pillar: red reads "ours", blue reads "enemy" (a yellow pillar would read the same to both rivals).
            val key = pillars.first { it.contains("-red-") }
            assertThat(sim.worldLabels.textFor(matchId, key, red.id)).isNotEqualTo(sim.worldLabels.textFor(matchId, key, blue.id))
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("no compass in hand: no hologram guide").isNull()
            sim.matchCompass.select(red.id, true)
            sim.matchCompass.select(blue.id, true)
            advance(25)
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("red holds the compass and is pointed at an enemy pillar").isNotNull()
            assertThat(sim.hologramGuide.guideOf(blue.id)).isNotNull()
            val card = chat(red)
            assertThat(card).contains("pillars").contains("tier").contains("last team")

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.Cancelled("clarity-test"))
            advance(40)
            assertThat(sim.worldLabels.labels(matchId)).isEmpty()
        }
    }

    @Test
    fun `annihilation - the navigation compass sits in hotbar slot 9 after start and respawn and guides only while held`() {
        scenario("clarity-annihilation-compass") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            advance(45)
            onTheMove(red, blue, yellow)

            for (who in listOf(red, blue)) {
                assertThat(sim.matchCompass.slotOf(who.id)).`as`("${who.name}: compass at index 8 after start").isEqualTo(8)
            }

            // Not held: neither the hologram guide nor the action-bar arrow.
            val arrowsBefore = sim.hud.changes(red.id, HudRegion.ACTION_BAR).size
            advance(45)
            onTheMove(red, blue, yellow)
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("compass not held: no guide").isNull()
            assertThat(sim.hud.changes(red.id, HudRegion.ACTION_BAR).size).`as`("compass not held: no arrow").isEqualTo(arrowsBefore)

            // Held: both appear.
            sim.matchCompass.select(red.id, true)
            advance(25)
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("compass held: guide shown").isNotNull()
            assertThat(sim.hud.changes(red.id, HudRegion.ACTION_BAR).size).`as`("compass held: arrow shown").isGreaterThan(arrowsBefore)

            // Switch to another slot: the guide goes away again.
            sim.matchCompass.select(red.id, false)
            advance(25)
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("compass put away: guide hidden").isNull()

            // Death takes the item, the respawn hands it back at index 8.
            sim.setMaxHealth(blue, 1.0)
            red.moveTo(blue.position + Vec3(0.0, 0.0, 1.5))
            red.holds("knight-sword")
            red.faces(blue)
            repeat(20) {
                if (eventsOf<PlayerKilled>().none { it.victim == blue.id }) {
                    red.leftClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<PlayerKilled>().any { it.victim == blue.id }).isTrue()
            assertThat(sim.matchCompass.slotOf(blue.id)).`as`("the compass is lost on death").isNull()
            advance(120)
            assertThat(eventsOf<PlayerRespawned>().any { it.playerId == blue.id }).isTrue()
            assertThat(sim.matchCompass.slotOf(blue.id)).`as`("compass back at index 8 after respawn").isEqualTo(8)
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["carry-tnt", "carry-mini-tnt", "steal-jewelry"])
    fun `siege modes hand out the navigation compass at index 8 and guide only while it is held`(mode: String) {
        scenario("clarity-compass-$mode") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch(mode, mapOf(red to "red", blue to "blue"))
            advance(45)
            onTheMove(red, blue)

            for (who in listOf(red, blue)) {
                assertThat(sim.matchCompass.slotOf(who.id)).`as`("$mode ${who.name}: compass at index 8").isEqualTo(8)
            }
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("$mode: compass not held, no guide").isNull()
            sim.matchCompass.select(red.id, true)
            advance(25)
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("$mode: compass held, guide shown").isNotNull()
            sim.matchCompass.select(red.id, false)
            advance(25)
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("$mode: compass put away, guide hidden").isNull()
        }
    }
}
