package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.sound.PitchArc
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/** ADR-0290: `play_sound` pitch_arc / viewers and `stop_sound`, through the real dispatcher and the recording sound gateway. */
class SoundPrimitivesScenarioTest {

    @Test
    fun `a 12 tick pitch arc plays one step per tick for the caster only, and stop_sound reaches the gateway`() {
        scenario("sound-primitives-arc") {
            val caster = player("caster", team = "red")
            val other = player("other", team = "blue")
            val dispatcher = sim.services.getOrThrow<MechanicDispatcher>()
            val key = "minecraft:block.note_block.harp"
            val ctx = MechanicContext(actorId = caster.id, origin = caster.position, worldId = caster.world)

            sim.playerSounds.clear()
            dispatcher.dispatch(
                listOf(
                    Mechanic.PlaySound(
                        targets = Targeter.Caster,
                        soundId = SoundId(key),
                        pitchArc = PitchArc(from = 0.6, to = 1.8, ticks = 12, ease = Easing.LINEAR),
                        viewers = Audience.CASTER,
                    ),
                ),
                ctx,
            )
            advance(13)

            val heard = sim.playerSounds.calls
            assertThat(heard.map { it.playerId }.toSet()).containsExactly(caster.id)
            assertThat(heard).hasSize(13)
            val expected = (0..12).map { 0.6 + (1.8 - 0.6) * it / 12.0 }
            heard.map { it.sound.pitch }.zip(expected).forEach { (actual, want) ->
                assertThat(actual).isCloseTo(want, Offset.offset(1e-9))
            }
            assertThat(heard.none { it.playerId == other.id }).isTrue()

            dispatcher.dispatch(listOf(Mechanic.StopSound(targets = Targeter.Caster, soundId = SoundId(key))), ctx)
            assertThat(sim.playerSounds.stops).containsExactly(caster.id to key)
        }
    }
}
