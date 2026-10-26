package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
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
            val matchId = startMatch("skirmish", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val norma = sim.services.getOrThrow<IMatchNormaStore>()
            assertThat(stars.get(matchId, red.id)).`as`("0 stars at the start").isEqualTo(0)
            assertThat(norma.get(matchId, red.id).level).isEqualTo(1)

            // (a) Red stands on a generator of the stage: its pops (20 s = 400 ticks) are collected on the spot.
            val generators = sim.services.getOrThrow<IGeneratorRepository>().listForMatch(matchId)
            val spot = generators.maxBy { it.position.x }.position
            red.moveTo(spot)
            blue.moveTo(generators.minBy { it.position.x }.position + Vec3(-40.0, 0.0, 0.0))
            advance(450)
            assertThat(eventsOf<StarsCollected>().filter { it.playerId == red.id }).`as`("red collected a pop").isNotEmpty()
            assertThat(stars.get(matchId, red.id)).isGreaterThanOrEqualTo(1)
            assertThat(stars.get(matchId, blue.id)).`as`("blue is far from every generator").isEqualTo(0)

            // (b) Carrying 40 stars, blue is killed by red: half the stack (death_drop_ratio 0.5) fountains out as 16 items, blue keeps 20.
            stars.add(matchId, blue.id, 40)
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
            assertThat(stars.get(matchId, blue.id)).`as`("half of the victim's carried stars burst out").isEqualTo(20)

            // The burst lies on the map as items; being a few blocks away takes nothing (pickup is the item pickup only).
            val lyingBurst = sim.starDrops.lying().filter { it.world == deathWorld }
            assertThat(lyingBurst.filter { it.amount <= 2 }.sumOf { it.amount }).`as`("20 stars lie on the map as burst items").isEqualTo(20)
            assertThat(lyingBurst.count { it.amount <= 2 }).`as`("the fountain throws 16 separate star items").isEqualTo(16)
            // The flings differ per star: random azimuth, speed and elevation (some almost flat, some nearly vertical).
            val flings = lyingBurst.filter { it.amount <= 2 }.mapNotNull { it.velocity }
            assertThat(flings).`as`("every burst star is thrown").hasSize(16)
            val speeds = flings.map { it.distance(Vec3(0.0, 0.0, 0.0)) }
            assertThat(speeds.max() - speeds.min()).`as`("launch speeds spread widely").isGreaterThan(0.15)
            assertThat(flings.map { it.y }.let { it.max() - it.min() }).`as`("vertical components spread widely").isGreaterThan(0.2)
            assertThat(flings.map { Math.atan2(it.z, it.x) }.distinct().size).`as`("directions all differ").isGreaterThan(flings.size - 2)
            assertThat(flings.minOf { it.y }).`as`("downward launch is clamped").isGreaterThanOrEqualTo(-0.05)
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

            // (c) Red carries enough stars for its next star goal: the level goes up by one, stars are kept.
            val levelBefore = norma.get(matchId, red.id).level
            val target = levelBefore + 1
            stars.add(matchId, red.id, 100)
            sim.eventBus.publish(StarsCollected(matchId, red.id, 100, stars.get(matchId, red.id)))
            advance(2)
            assertThat(eventsOf<NormaLevelUp>().any { it.playerId == red.id && it.level == target }).isTrue()
            assertThat(norma.get(matchId, red.id).level).isEqualTo(target)
            val shown = sim.normaPresentation.levelUps.filter { it.player == red.id && it.level == target }
            assertThat(shown).`as`("the level-up is presented (title / sound / burst / broadcast) once").hasSize(1)
            assertThat(shown.single().audience).`as`("the whole match sees the broadcast").contains(red.id, blue.id)
            assertThat(stars.get(matchId, red.id)).`as`("stars are not consumed by a level-up").isGreaterThanOrEqualTo(100)
        }
    }

    @Test
    fun `at the time limit the higher norma level wins, then stars carried`() {
        scenario("norma-time-limit-level") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("skirmish", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<IMatchStarStore>()

            // Red clears level 1 (10 stars); blue carries 9 stars at level 1. Level beats stars.
            stars.add(matchId, red.id, 10)
            sim.eventBus.publish(StarsCollected(matchId, red.id, 10, 10))
            stars.add(matchId, blue.id, 9)
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
            val matchId = startMatch("skirmish", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            stars.add(matchId, blue.id, 2)
            advance(12_100)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).isEqualTo(EndReason.NormaTimeLimit(TeamId("blue")))
        }
        scenario("norma-time-limit-draw") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("skirmish", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            advance(12_100)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).isEqualTo(EndReason.NormaTimeLimit(null))
            assertThat(ended.winningTeam).isNull()
        }
    }

    @Test
    fun `stars of a finished match are gone and the next match's pops can be picked up`() {
        scenario("stars-second-match") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val first = startMatch("skirmish", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            // Pops pile up unattended in the first match, then it ends on the time limit.
            advance(450)
            assertThat(sim.starDrops.lying()).`as`("pops lie on the map during the match").isNotEmpty()
            advance(12_100)
            assertThat(eventsOf<MatchEnded>().any { it.matchId == first }).isTrue()
            assertThat(sim.starDrops.lying()).`as`("no star of the ended match is left lying").isEmpty()

            // A fresh match on the same stage: its pops are collected as usual.
            val second = startMatch("skirmish", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val generators = sim.services.getOrThrow<IGeneratorRepository>().listForMatch(second)
            val spot = generators.maxBy { it.position.x }.position
            red.moveTo(spot)
            advance(450)
            assertThat(stars.get(second, red.id)).`as`("red picks up a pop in the second match").isGreaterThanOrEqualTo(1)
        }
    }
}
