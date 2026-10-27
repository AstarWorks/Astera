package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IActionBarChannel
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.post
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * FX-12: the victim's and the killer's action-bar feedback, next to the attacker's hit marker
 * ([HitFeedbackListener]).
 *
 *  - **Damage taken**: who hit you and for how much, for [HURT_TTL_TICKS].
 *  - **Low HP**: no text and no sound (founder 2026-10-28); at or below [LOW_HP_FRACTION] of max HP the screen
 *    edge goes red through [IPlayerFeedbackGateway.setLowHealthVignette], and it is cleared on recovery, death,
 *    match end and quit.
 *  - **Kill confirm**: the victim's name in gold, above everything else.
 *
 * Every action-bar write goes through [IActionBarChannel] (ADR-0279), so the compositor keeps the HP line on screen and a
 * higher-priority toast is never overwritten by a lower one. Players who switched the HUD off in Settings
 * ([hudVisible] false) get no action-bar lines and no vignette.
 */
public class CombatHudFeedbackListener(
    private val actionBar: IActionBarChannel,
    private val health: IPlayerHealthGateway,
    private val players: IPlayerGateway,
    private val feedback: IPlayerFeedbackGateway,
    private val hudVisible: (PlayerId) -> Boolean = { true },
) {
    private val vignetteOn: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    public fun onDamage(event: DamageDealt) {
        val resolved = event.resolved
        if (resolved.wasBlocked || resolved.finalAmount <= 0.0) return
        val victim = event.target
        val hp = health.getCurrentHealth(victim) ?: return
        if (hp <= 0.0) return // the death flow owns the screen now
        if (!hudVisible(victim)) return
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

    /** Every HP change (damage, regen, respawn refill) re-evaluates the vignette. */
    public fun onHealthChanged(event: PlayerHealthChanged) {
        val player = event.playerId
        val max = health.getMaxHealth(player)
        val low = max != null && max > 0.0 && event.newHp > 0.0 && event.newHp / max <= LOW_HP_FRACTION && hudVisible(player)
        if (low) {
            if (vignetteOn.add(player)) feedback.setLowHealthVignette(player, true)
        } else {
            clear(player)
        }
    }

    public fun onKilled(event: PlayerKilled) {
        clear(event.victim)
        val killer = event.killer ?: return
        if (killer == event.victim || !hudVisible(killer)) return
        val victimName = players.nameOf(event.victim) ?: return
        actionBar.post(killer, KEY_KILL, mapOf("name" to victimName), priority = PRIORITY_KILL, ttlTicks = KILL_TTL_TICKS)
    }

    public fun onMatchEnded(@Suppress("UNUSED_PARAMETER") event: MatchEnded) {
        vignetteOn.toList().forEach { clear(it) }
    }

    public fun onQuit(event: PlayerQuitServer) {
        clear(event.playerId)
    }

    private fun clear(player: PlayerId) {
        if (vignetteOn.remove(player)) feedback.setLowHealthVignette(player, false)
    }

    public companion object {
        /** At or below this share of max HP the red screen-edge vignette shows. */
        public const val LOW_HP_FRACTION: Double = 0.3

        // Toasts are >= 1 (IActionBarChannel); hurt < kill so the most urgent line wins a collision.
        public const val PRIORITY_HURT: Int = 2
        public const val PRIORITY_KILL: Int = 3

        public const val HURT_TTL_TICKS: Int = 25
        public const val KILL_TTL_TICKS: Int = 40

        public val KEY_HURT_BY: MessageKey = MessageKey("astera.hud.combat.hurt-by")
        public val KEY_HURT: MessageKey = MessageKey("astera.hud.combat.hurt")
        public val KEY_KILL: MessageKey = MessageKey("astera.hud.combat.kill")
    }
}
