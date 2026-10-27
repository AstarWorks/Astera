package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IActionBarChannel
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.post
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * FX-12: the victim's and the killer's action-bar feedback, next to the attacker's hit marker
 * ([HitFeedbackListener]).
 *
 *  - **Damage taken**: who hit you and for how much, for [HURT_TTL_TICKS].
 *  - **Low HP**: a red warning below [LOW_HP_FRACTION] of max HP, with a warning sound at most every
 *    [LOW_HP_SOUND_COOLDOWN_TICKS].
 *  - **Kill confirm**: the victim's name in gold, above everything else.
 *
 * Every write goes through [IActionBarChannel] (ADR-0279), so the compositor keeps the HP line on screen and a
 * higher-priority toast is never overwritten by a lower one. Players who switched the HUD off in Settings
 * ([hudVisible] false) get no action-bar lines; sounds stay.
 */
public class CombatHudFeedbackListener(
    private val actionBar: IActionBarChannel,
    private val health: IPlayerHealthGateway,
    private val players: IPlayerGateway,
    private val sounds: IPresentation,
    private val hudVisible: (PlayerId) -> Boolean = { true },
) {
    private val lastLowHpSoundTick: MutableMap<PlayerId, Long> = ConcurrentHashMap()

    public fun onDamage(event: DamageDealt) {
        val resolved = event.resolved
        if (resolved.wasBlocked || resolved.finalAmount <= 0.0) return
        val victim = event.target
        val hp = health.getCurrentHealth(victim) ?: return
        if (hp <= 0.0) return // the death flow owns the screen now
        val visible = hudVisible(victim)
        if (visible) {
            val amount = ceil(resolved.finalAmount).toInt().toString()
            val attackerName = event.attacker?.takeIf { it != victim }?.let { players.nameOf(it) }
            if (attackerName != null) {
                actionBar.post(
                    victim, KEY_HURT_BY, mapOf("name" to attackerName, "amount" to amount),
                    priority = PRIORITY_HURT, ttlTicks = HURT_TTL_TICKS,
                )
            } else {
                actionBar.post(victim, KEY_HURT, mapOf("amount" to amount), priority = PRIORITY_HURT, ttlTicks = HURT_TTL_TICKS)
            }
        }
        val max = health.getMaxHealth(victim) ?: return
        if (max <= 0.0 || hp / max > LOW_HP_FRACTION) return
        if (visible) {
            actionBar.post(
                victim, KEY_LOW_HP, mapOf("hp" to ceil(hp).toInt().toString()),
                priority = PRIORITY_LOW_HP, ttlTicks = LOW_HP_TTL_TICKS,
            )
        }
        val last = lastLowHpSoundTick[victim]
        if (last == null || event.atTick - last >= LOW_HP_SOUND_COOLDOWN_TICKS) {
            lastLowHpSoundTick[victim] = event.atTick
            sounds.play(PresentationMoments.OBJECTIVE_WARN, victim)
        }
    }

    public fun onKilled(event: PlayerKilled) {
        lastLowHpSoundTick -= event.victim
        val killer = event.killer ?: return
        if (killer == event.victim || !hudVisible(killer)) return
        val victimName = players.nameOf(event.victim) ?: return
        actionBar.post(killer, KEY_KILL, mapOf("name" to victimName), priority = PRIORITY_KILL, ttlTicks = KILL_TTL_TICKS)
    }

    public fun onQuit(event: PlayerQuitServer) {
        lastLowHpSoundTick -= event.playerId
    }

    public companion object {
        /** Below this share of max HP the low-HP warning shows. */
        public const val LOW_HP_FRACTION: Double = 0.3
        public const val LOW_HP_SOUND_COOLDOWN_TICKS: Long = 100L

        // Toasts are >= 1 (IActionBarChannel); hurt < low-HP < kill so the most urgent line wins a collision.
        public const val PRIORITY_HURT: Int = 2
        public const val PRIORITY_LOW_HP: Int = 3
        public const val PRIORITY_KILL: Int = 4

        public const val HURT_TTL_TICKS: Int = 25
        public const val LOW_HP_TTL_TICKS: Int = 30
        public const val KILL_TTL_TICKS: Int = 40

        public val KEY_HURT_BY: MessageKey = MessageKey("astera.hud.combat.hurt-by")
        public val KEY_HURT: MessageKey = MessageKey("astera.hud.combat.hurt")
        public val KEY_LOW_HP: MessageKey = MessageKey("astera.hud.combat.low-hp")
        public val KEY_KILL: MessageKey = MessageKey("astera.hud.combat.kill")
    }
}
