package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.event.CooldownExpired
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.testfixtures.FakeSkillRegistry
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.event.SkillCooldownStarted
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.testfixtures.effect.FakePlayerSoundGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SkillReadyCueListenerTest {

    private val player = PlayerId(UUID.randomUUID())
    private val sounds = FakePlayerSoundGateway()
    private val skills = FakeSkillRegistry()
    private val listener = SkillReadyCueListener(sounds, skills)

    private fun spec(id: String, readyCue: Boolean? = null) = SkillSpec(
        id = SkillId(id),
        name = LocalizedText(emptyMap()),
        description = LocalizedText(emptyMap()),
        archetype = SkillArchetype.PROJECTILE,
        cooldownTicks = 10,
        params = SkillParams.ProjectileParams(launchSpeed = 20.0, maxLifetimeTicks = 60),
        phases = PhaseMechanics.Projectile(),
        readyCue = readyCue,
    ).also { skills.register(it) }

    private fun cycle(skill: String, duration: Int) {
        listener.onStarted(SkillCooldownStarted(player, SkillId(skill), null, null, duration, null, atTick = 0))
        listener.onExpired(CooldownExpired(player, CooldownKey.Skill(skill), expiredAtTick = duration.toLong()))
    }

    @Test
    fun `a cooldown of three seconds or more chimes when it ends`() {
        spec("big-spell")
        cycle("big-spell", 60)
        assertThat(sounds.calls).hasSize(1)
        assertThat(sounds.calls.single().playerId).isEqualTo(player)
    }

    @Test
    fun `a fast cooldown stays silent by default`() {
        spec("rapid")
        cycle("rapid", 59)
        assertThat(sounds.calls).isEmpty()
    }

    @Test
    fun `ready_cue overrides the default both ways`() {
        spec("quiet-big", readyCue = false)
        spec("loud-small", readyCue = true)
        cycle("quiet-big", 200)
        cycle("loud-small", 10)
        assertThat(sounds.calls).hasSize(1)
    }

    @Test
    fun `an expiry nobody started and non-skill cooldowns are ignored`() {
        spec("big-spell")
        listener.onExpired(CooldownExpired(player, CooldownKey.Skill("big-spell"), expiredAtTick = 5))
        listener.onExpired(CooldownExpired(player, CooldownKey.Status("burn"), expiredAtTick = 5))
        assertThat(sounds.calls).isEmpty()
    }
}
