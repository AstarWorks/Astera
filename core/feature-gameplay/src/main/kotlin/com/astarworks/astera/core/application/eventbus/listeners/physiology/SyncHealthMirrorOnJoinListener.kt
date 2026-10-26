package com.astarworks.astera.core.application.eventbus.listeners.physiology

import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.slf4j.LoggerFactory

/**
 * Syncs the vanilla health bar (the UI mirror of the Astera HP container, ADR-0149) when a player joins.
 *
 * Bukkit `player.health` at join time is whatever the server's own playerdata file last held, which for the
 * Astera mirror is the leftover of the previous session (down to the 1.0 floor of a death), while the Astera
 * container of a fresh process is empty ("full at the default max", see `HpStatusHudService`). The mirror is only
 * written on a mutation, so without this a direct login shows 1-3 hearts next to a full HP line. A container with no
 * recorded HP is made full; a recorded one is re-mirrored unchanged.
 */
public class SyncHealthMirrorOnJoinListener(
    /** Resolved per event so the Paper overlay (priority 100) is used whenever it is registered. */
    private val health: () -> IPlayerHealthGateway?,
    private val defaultMaxHp: Double = DEFAULT_MAX_HP,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerJoinedServer) {
        val playerId = event.playerId
        val health = health() ?: return
        try {
            val max = health.getMaxHealth(playerId) ?: run {
                health.setMaxHealth(playerId, defaultMaxHp)
                defaultMaxHp
            }
            val current = health.getCurrentHealth(playerId) ?: max
            health.setHealth(playerId, current, HealthChangeSource.Other("join-mirror-sync"))
        } catch (ex: Exception) {
            log.warn("Health mirror sync failed for {}", playerId, ex)
        }
    }

    public companion object {
        public const val DEFAULT_MAX_HP: Double = com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP
    }
}
