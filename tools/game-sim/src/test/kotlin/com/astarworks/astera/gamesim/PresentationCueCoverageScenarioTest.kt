package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationTable
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationVisuals
import com.astarworks.astera.core.application.service.presentation.LoggingPresentationVisuals
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.ParticleVisibility
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * FX-05 / FX-04: every game moment has a row in `content/presentation/events.yaml` whose sound cues and particle
 * presets exist, and the star moments (pop at a generator, pickup, burst) trigger their cue, particles and text for
 * the right players, honouring the particle-visibility setting.
 */
class PresentationCueCoverageScenarioTest {

    private fun ScenarioScope.visuals(): LoggingPresentationVisuals =
        sim.services.getOrThrow<IPresentationVisuals>() as LoggingPresentationVisuals

    private fun ScenarioScope.heard(who: SimEntity, cue: SoundId): Int {
        val def = checkNotNull(sim.services.getOrThrow<ISoundRegistry>().find(cue)) { "cue $cue missing" }
        return sim.playerSounds.calls.count { it.playerId == who.id && it.sound.key == def.resourceKey && it.sound.volume > 0.0 }
    }

    @Test
    fun `every moment has a row and every cue and particle preset it names is defined`() {
        scenario("presentation-coverage") {
            val table = sim.services.getOrThrow<IPresentationTable>()
            val sounds = sim.services.getOrThrow<ISoundRegistry>()
            for (moment in PresentationMoments.ALL) {
                val entry = checkNotNull(table.entry(moment)) { "moment '$moment' has no row in content/presentation/events.yaml" }
                assertThat(entry.cue ?: entry.audienceCue).`as`("moment '$moment' has a sound").isNotNull()
                listOfNotNull(entry.cue, entry.audienceCue).forEach {
                    assertThat(sounds.find(it)).`as`("cue $it of '$moment' is defined under content/sounds").isNotNull()
                }
                entry.particle?.let { assertThat(table.preset(it)).`as`("preset $it of '$moment'").isNotNull() }
            }
        }
    }

    @Test
    fun `star pop, pickup and burst each trigger their cue, particles and text`() {
        scenario("presentation-stars") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("norma", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val log = visuals()

            // Pop + pickup: red stands on the east generator.
            red.moveTo(Vec3(12.5, 65.0, 0.5))
            blue.moveTo(Vec3(-34.0, 65.0, 0.0))
            advance(650)
            assertThat(heard(red, com.astarworks.astera.core.domain.effect.SoundCues.STAR_POP)).`as`("pop is heard nearby").isGreaterThanOrEqualTo(1)
            val particleIds = log.calls.filterIsInstance<LoggingPresentationVisuals.Call.Particles>()
            assertThat(particleIds.any { it.preset.id == "star-pop" && red.id in it.viewers }).isTrue()
            assertThat(particleIds.any { it.preset.id == "star-pickup" && red.id in it.viewers }).isTrue()
            assertThat(log.calls.filterIsInstance<LoggingPresentationVisuals.Call.ActionBar>().any {
                it.player == red.id && it.key.value == "astera.match.star.collected" && it.args["amount"] != null
            }).`as`("+N star action bar").isTrue()

            // Burst: blue carries 6 stars and is killed by red.
            log.calls.clear()
            stars.add(matchId, blue.id, 6)
            sim.setMaxHealth(blue, 1.0)
            red.moveTo(blue.position + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            repeat(20) {
                if (eventsOf<PlayerKilled>().none { it.victim == blue.id }) {
                    red.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<PlayerKilled>().any { it.victim == blue.id }).isTrue()
            val bars = log.calls.filterIsInstance<LoggingPresentationVisuals.Call.ActionBar>()
            assertThat(bars.any { it.player == blue.id && it.key.value == "astera.presentation.star-burst.victim" }).`as`("victim feedback").isTrue()
            assertThat(bars.any { it.player == red.id && it.key.value == "astera.presentation.star-burst.killer" }).`as`("killer feedback").isTrue()
            val burst = log.calls.filterIsInstance<LoggingPresentationVisuals.Call.Particles>()
            assertThat(burst.any { it.preset.id == "star-burst" && blue.id in it.viewers }).`as`("radial pop at the death spot").isTrue()
            assertThat(heard(blue, com.astarworks.astera.core.domain.effect.SoundCues.STAR_BURST)).isGreaterThanOrEqualTo(1)
            assertThat(heard(red, com.astarworks.astera.core.domain.effect.SoundCues.STAR_BURST_GAIN)).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `consecutive pickups rise in pitch`() {
        scenario("presentation-pickup-chain") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("norma", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val presentation = sim.services.getOrThrow<IPresentation>()
            sim.playerSounds.clear()
            repeat(3) { presentation.play(PresentationMoments.STAR_PICKUP, red.id) }
            val pitches = sim.playerSounds.calls
                .filter { it.playerId == red.id && it.sound.key == "minecraft:entity.experience_orb.pickup" }
                .map { it.sound.pitch }
            assertThat(pitches).hasSize(3)
            assertThat(pitches[1]).isGreaterThan(pitches[0])
            assertThat(pitches[2]).isGreaterThan(pitches[1])
        }
    }

    @Test
    fun `particles respect each viewer's visibility setting`() {
        scenario("presentation-particle-visibility") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("norma", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val settings = sim.services.getOrThrow<IPlayerSettingsRepository>()
            settings.upsert(settings.findOrDefault(blue.id).copy(particleVisibility = ParticleVisibility.OwnOnly))
            val log = visuals()
            log.calls.clear()
            sim.services.getOrThrow<IPresentation>().play(
                PresentationMoments.STAR_BURST, red.id, at = Vec3(0.0, 65.0, 0.0), audience = listOf(blue.id),
            )
            val particles = log.calls.filterIsInstance<LoggingPresentationVisuals.Call.Particles>().single()
            assertThat(particles.viewers).`as`("blue hides other players' effects").containsExactly(red.id)
        }
    }

    @Test
    fun `death, respawn and player level-up are seen as well as heard`() {
        scenario("presentation-death-respawn") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("norma", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val log = visuals()
            val presentation = sim.services.getOrThrow<IPresentation>()
            log.calls.clear()

            presentation.play(PresentationMoments.DEATH, blue.id, audience = listOf(red.id))
            presentation.play(PresentationMoments.RESPAWN, blue.id, at = Vec3(0.0, 65.0, 0.0))
            presentation.play(PresentationMoments.PLAYER_LEVEL_UP, red.id)

            val particles = log.calls.filterIsInstance<LoggingPresentationVisuals.Call.Particles>()
            assertThat(particles.any { it.preset.id == "death-soul" && blue.id in it.viewers && red.id in it.viewers }).`as`("souls rise for victim and killer").isTrue()
            assertThat(particles.any { it.preset.id == "respawn-glint" && blue.id in it.viewers }).isTrue()
            assertThat(particles.any { it.preset.id == "player-level-up" && red.id in it.viewers }).isTrue()
            assertThat(log.calls.filterIsInstance<LoggingPresentationVisuals.Call.ActionBar>().any {
                it.player == blue.id && it.key.value == "astera.presentation.respawn"
            }).`as`("respawn line").isTrue()
        }
    }
}
