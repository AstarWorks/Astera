package com.astarworks.astera.combat.application.eventbus.listeners.status

import com.astarworks.astera.combat.application.service.StatusCodex
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import java.util.concurrent.ConcurrentHashMap

/**
 * UI-12: when a status lands on a player, one chat line names it (hover = effect, click = `/astera status <id>`).
 * The same status is announced at most once per [COOLDOWN_TICKS] per player so DoT refreshes and stack ticks
 * do not spam the chat; different statuses have independent cooldowns.
 */
public class StatusAppliedLookupListener(
    private val codex: StatusCodex,
    private val players: IPlayerGateway,
) {
    private val lastNotified = ConcurrentHashMap<PlayerId, ConcurrentHashMap<StatusEffectId, Long>>()

    public fun handle(event: StatusApplied) {
        val id = event.effect.spec.id
        val perPlayer = lastNotified.computeIfAbsent(event.target) { ConcurrentHashMap() }
        val last = perPlayer[id]
        if (last != null && event.atTick - last in 0 until COOLDOWN_TICKS) return
        perPlayer[id] = event.atTick
        players.sendMessage(event.target, codex.appliedLine(id, event.target))
    }

    public fun forget(player: PlayerId) {
        lastNotified.remove(player)
    }

    public companion object {
        /** 15 s at 20 TPS. */
        public const val COOLDOWN_TICKS: Long = 300
    }
}
