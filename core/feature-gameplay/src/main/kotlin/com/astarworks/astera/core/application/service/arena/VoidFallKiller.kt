package com.astarworks.astera.core.application.service.arena

import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource

/**
 * Kill-plane rule of the sky-island arenas (ADR-0246): a player who dropped below the stage's `kill_y` dies through
 * the Astera health container, the same path as every other death (`PlayerHealthChanged` -> `PlayerKilled` ->
 * respawn flow). Host-neutral so the Paper listener and game-sim share it.
 */
public class VoidFallKiller(
    private val health: () -> IPlayerHealthGateway?,
    private val currentTick: () -> Long,
) {
    private val lastKill = java.util.concurrent.ConcurrentHashMap<PlayerId, Long>()

    /**
     * Kills [playerId] unless already dead or killed within the last second. [beforeKill] runs right before the
     * damage (the death listeners read the victim's position synchronously). Returns whether damage was applied.
     */
    public fun kill(playerId: PlayerId, beforeKill: () -> Unit = {}): Boolean {
        val now = currentTick()
        val previous = lastKill[playerId]
        if (previous != null && now - previous in 0 until COOLDOWN_TICKS) return false
        val gateway = health() ?: return false
        // No recorded HP yet (never damaged / healed): the player is alive at full health.
        val current = gateway.getCurrentHealth(playerId)
        if (current != null && current <= 0.0) return false
        lastKill[playerId] = now
        beforeKill()
        gateway.damage(playerId, VOID_DAMAGE, HealthChangeSource.Other(SOURCE_LABEL))
        return true
    }

    public fun forget(playerId: PlayerId) {
        lastKill.remove(playerId)
    }

    public companion object {
        public const val SOURCE_LABEL: String = "vanilla-void"
        private const val VOID_DAMAGE: Double = 1_000_000.0
        private const val COOLDOWN_TICKS: Long = 20L
    }
}
