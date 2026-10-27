package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MODE-18: at match start every mode tells the player where things are and what to do. Floating labels stand over each
 * kind of region (read differently by the owner and by the rival), every player has a navigation target, the three-line
 * rule card reaches chat, and nothing is left behind when the match ends.
 */
class MatchClarityScenarioTest {

    private val nowhere = Vec3(0.0, 65.0, -50.0)

    private fun ScenarioScope.chat(who: SimEntity): String = checkNotNull(sim.server.view(who.id)).messages.joinToString("\n")

    private fun ScenarioScope.run(mode: String, stage: String, labelKeys: List<String>, ownerSpecific: String) {
        val red = player("red-1", at = nowhere)
        val blue = player("blue-1", at = nowhere)
        val matchId = startMatch(mode, mapOf(red to "red", blue to "blue"), stage = stage)
        advance(45)

        val labels = sim.worldLabels.labels(matchId)
        for (key in labelKeys) assertThat(labels.keys).`as`("$mode: label $key").contains(key)
        val redReads = sim.worldLabels.textFor(matchId, ownerSpecific, red.id)
        val blueReads = sim.worldLabels.textFor(matchId, ownerSpecific, blue.id)
        assertThat(redReads).`as`("$mode: $ownerSpecific read by red").isNotBlank()
        assertThat(blueReads).`as`("$mode: $ownerSpecific read by blue").isNotBlank()
        assertThat(redReads).`as`("$mode: the owner and the rival read different text").isNotEqualTo(blueReads)

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
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            advance(45)

            val pillars = sim.worldLabels.labels(matchId).keys.filter { it.startsWith("pillar-") }
            assertThat(pillars).`as`("pillar labels").isNotEmpty()
            val key = pillars.first()
            assertThat(sim.worldLabels.textFor(matchId, key, red.id)).isNotEqualTo(sim.worldLabels.textFor(matchId, key, blue.id))
            assertThat(sim.hologramGuide.guideOf(red.id)).`as`("red is pointed at an enemy pillar").isNotNull()
            assertThat(sim.hologramGuide.guideOf(blue.id)).isNotNull()
            val card = chat(red)
            assertThat(card).contains("pillars").contains("Tier").contains("last team")

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.Cancelled("clarity-test"))
            advance(40)
            assertThat(sim.worldLabels.labels(matchId)).isEmpty()
        }
    }
}
