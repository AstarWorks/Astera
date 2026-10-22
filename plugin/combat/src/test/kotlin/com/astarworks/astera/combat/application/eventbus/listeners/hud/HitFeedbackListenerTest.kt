package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.application.service.InMemoryDamageIndicatorGateway
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.DamageIndicatorStyle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HitFeedbackListenerTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())

    private class RecordingSounds : IPlayerSoundGateway {
        val played = mutableListOf<Pair<PlayerId, SoundSpec>>()
        override fun playForViewer(playerId: PlayerId, sound: SoundSpec, position: Vec3?, world: WorldId?) {
            played += playerId to sound
        }
    }

    private val indicators = InMemoryDamageIndicatorGateway()
    private val sounds = RecordingSounds()
    private val listener = HitFeedbackListener(indicators, sounds)

    private fun hit(
        amount: Double,
        critical: Boolean = false,
        blocked: Boolean = false,
        from: PlayerId? = attacker,
        to: PlayerId = target,
    ) = DamageDealt(
        attacker = from,
        target = to,
        resolved = ResolvedDamage(amount, DamageAttribute.PHYSICAL, wasBlocked = blocked, wasCritical = critical),
        source = DamageSource.Item(ItemId("sword"), attackerId = from ?: attacker),
        atTick = 1L,
    )

    @Test
    fun `a hit shows the rounded-up amount to the attacker and plays the hit marker`() {
        listener.onDamage(hit(33.2))

        assertThat(indicators.shown()).containsExactly(
            InMemoryDamageIndicatorGateway.Shown(attacker, target, 34, DamageIndicatorStyle.NORMAL),
        )
        assertThat(sounds.played).containsExactly(attacker to HitFeedbackListener.HIT_SOUND)
    }

    @Test
    fun `critical and blocked hits are styled and sounded differently`() {
        listener.onDamage(hit(50.0, critical = true))
        listener.onDamage(hit(0.0, blocked = true))

        assertThat(indicators.shown().map { it.style })
            .containsExactly(DamageIndicatorStyle.CRITICAL, DamageIndicatorStyle.BLOCKED)
        assertThat(indicators.shown().last().amount).isZero()
        assertThat(sounds.played.map { it.second })
            .containsExactly(HitFeedbackListener.CRIT_SOUND, HitFeedbackListener.BLOCKED_SOUND)
    }

    @Test
    fun `environmental damage and self damage give no feedback`() {
        listener.onDamage(hit(10.0, from = null))
        listener.onDamage(hit(10.0, from = attacker, to = attacker))

        assertThat(indicators.shown()).isEmpty()
        assertThat(sounds.played).isEmpty()
    }

    @Test
    fun `a kill plays the kill sound to the killer only`() {
        val source = DamageSource.Item(ItemId("sword"), attackerId = attacker)
        listener.onKilled(PlayerKilled(victim = target, killer = attacker, source = source, atTick = 1L))
        listener.onKilled(PlayerKilled(victim = target, killer = null, source = source, atTick = 1L))
        listener.onKilled(PlayerKilled(victim = target, killer = target, source = source, atTick = 1L))

        assertThat(sounds.played).containsExactly(attacker to HitFeedbackListener.KILL_SOUND)
    }
}
