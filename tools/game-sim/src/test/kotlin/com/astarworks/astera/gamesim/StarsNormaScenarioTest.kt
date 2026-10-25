package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-match stars and Norma levels (docs/game-design/stars-and-norma.md): a generator pop lies on the map and the
 * participant standing there takes it by item pickup (no proximity crediting); a kill bursts the victim's carried stars into drops anyone can pick up; the
 * Norma level goal (stars carried) levels the player up; nothing outlives the match.
 */
class StarsNormaScenarioTest {

    @Test
    fun `generator pops are picked up, a kill bursts the carried stars, the star goal levels up and the match clears all`() {
        scenario("stars-and-norma") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("norma", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val norma = sim.services.getOrThrow<IMatchNormaStore>()
            assertThat(stars.get(matchId, red.id)).`as`("0 stars at the start").isEqualTo(0)
            assertThat(norma.get(matchId, red.id).level).isEqualTo(1)

            // (a) Red stands on the east generator (12.5, 65, 0.5): its pops (30 s = 600 ticks) are collected on the spot.
            red.moveTo(Vec3(12.5, 65.0, 0.5))
            blue.moveTo(Vec3(-34.0, 65.0, 0.0))
            advance(650)
            assertThat(eventsOf<StarsCollected>().filter { it.playerId == red.id }).`as`("red collected a pop").isNotEmpty()
            assertThat(stars.get(matchId, red.id)).isGreaterThanOrEqualTo(1)
            assertThat(stars.get(matchId, blue.id)).`as`("blue is far from every generator").isEqualTo(0)

            // (b) Carrying 6 stars, blue is killed by red: the whole stack bursts out of blue, blue keeps 0.
            stars.add(matchId, blue.id, 6)
            sim.setMaxHealth(blue, 1.0)
            val deathSpot = blue.position
            val deathWorld = blue.world
            red.moveTo(deathSpot + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            repeat(20) {
                if (eventsOf<PlayerKilled>().none { it.victim == blue.id }) {
                    red.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<PlayerKilled>().any { it.victim == blue.id }).isTrue()
            assertThat(stars.get(matchId, blue.id)).`as`("the victim's carried stars burst out").isEqualTo(0)

            // The burst lies on the map as items; being a few blocks away takes nothing (pickup is the item pickup only).
            val lyingBurst = sim.starDrops.lying().filter { it.world == deathWorld }
            assertThat(lyingBurst).`as`("the burst lies on the map as star items").isNotEmpty()
            val before = stars.get(matchId, red.id)
            advance(40)
            assertThat(stars.get(matchId, red.id)).`as`("red, 6 blocks away, takes nothing").isEqualTo(before)

            // Red walks the death spot (centre + a ring) and picks the burst drops up.
            val lyingBefore = sim.starDrops.lying().size
            red.moveTo(deathSpot)
            advance(10)
            for (i in 0 until 12) {
                val a = i * Math.PI / 6
                red.moveTo(Vec3(deathSpot.x + cos(a) * 1.5, deathSpot.y, deathSpot.z + sin(a) * 1.5))
                advance(10)
            }
            assertThat(stars.get(matchId, red.id)).`as`("red picked up burst stars").isGreaterThan(before)
            assertThat(sim.starDrops.lying().size).`as`("the picked-up items are gone from the map").isLessThan(lyingBefore)

            // (c) Red carries enough stars for the level-1 star goal (3 by default): the level goes 1 -> 2, stars are kept.
            stars.add(matchId, red.id, 20)
            sim.eventBus.publish(StarsCollected(matchId, red.id, 20, stars.get(matchId, red.id)))
            advance(2)
            assertThat(eventsOf<NormaLevelUp>().any { it.playerId == red.id && it.level == 2 }).isTrue()
            assertThat(norma.get(matchId, red.id).level).isEqualTo(2)
            val shown = sim.normaPresentation.levelUps.filter { it.player == red.id && it.level == 2 }
            assertThat(shown).`as`("the level-up is presented (title / sound / burst / broadcast) once").hasSize(1)
            assertThat(shown.single().audience).`as`("the whole match sees the broadcast").contains(red.id, blue.id)
            assertThat(stars.get(matchId, red.id)).`as`("stars are not consumed by a level-up").isGreaterThanOrEqualTo(20)
        }
    }

    @Test
    fun `at the time limit the higher norma level wins, then stars carried`() {
        scenario("norma-time-limit-level") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("norma", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<IMatchStarStore>()

            // Red clears level 1 (3 stars); blue carries 2 stars at level 1. Level beats stars.
            stars.add(matchId, red.id, 3)
            sim.eventBus.publish(StarsCollected(matchId, red.id, 3, 3))
            stars.add(matchId, blue.id, 2)
            advance(12_100)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).isEqualTo(EndReason.NormaTimeLimit(TeamId("red")))
            assertThat(ended.winningTeam).isEqualTo(TeamId("red"))
        }
    }

    @Test
    fun `at the time limit equal levels fall back to stars carried, and a full tie is a draw`() {
        scenario("norma-time-limit-stars") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("norma", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            stars.add(matchId, blue.id, 2)
            advance(12_100)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).isEqualTo(EndReason.NormaTimeLimit(TeamId("blue")))
        }
        scenario("norma-time-limit-draw") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("norma", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            advance(12_100)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).isEqualTo(EndReason.NormaTimeLimit(null))
            assertThat(ended.winningTeam).isNull()
        }
    }
}
