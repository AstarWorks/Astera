package com.astarworks.astera.core.application.service.admin

import com.astarworks.astera.core.application.port.outbound.admin.AdminGameMode
import com.astarworks.astera.core.application.port.outbound.admin.AdminOnlinePlayer
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IAdminPlayerGateway] for the headless simulator and unit tests
 * (F1-D, ADR-0183). Players are put "online" explicitly via [join]; every
 * mutation is recorded so tests can assert what the panel asked for.
 */
public class InMemoryAdminPlayerGateway : IAdminPlayerGateway {

    private val roster: MutableMap<PlayerId, String> = ConcurrentHashMap()
    private val modes: MutableMap<PlayerId, AdminGameMode> = ConcurrentHashMap()
    private val flying: MutableMap<PlayerId, Boolean> = ConcurrentHashMap()

    /** `(who, destination)` pairs in call order. */
    public val teleports: MutableList<Pair<PlayerId, PlayerId>> = mutableListOf()

    /** `(player, reason)` pairs in call order. */
    public val kicks: MutableList<Pair<PlayerId, String>> = mutableListOf()

    private val operators: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    public fun op(id: PlayerId) {
        operators += id
    }

    override fun isOperator(player: PlayerId): Boolean = player in operators

    public fun join(id: PlayerId, name: String) {
        roster[id] = name
    }

    public fun quit(id: PlayerId) {
        roster.remove(id)
    }

    public fun modeOf(id: PlayerId): AdminGameMode = modes[id] ?: AdminGameMode.SURVIVAL

    public fun isFlying(id: PlayerId): Boolean = flying[id] ?: false

    override fun onlinePlayers(): List<AdminOnlinePlayer> =
        roster.map { (id, name) -> AdminOnlinePlayer(id, name) }.sortedBy { it.name.lowercase() }

    override fun teleportToPlayer(who: PlayerId, destination: PlayerId): Boolean {
        if (who !in roster || destination !in roster) return false
        teleports += who to destination
        return true
    }

    override fun toggleGameMode(player: PlayerId): AdminGameMode? {
        if (player !in roster) return null
        val next = if (modeOf(player) == AdminGameMode.CREATIVE) AdminGameMode.SURVIVAL else AdminGameMode.CREATIVE
        modes[player] = next
        return next
    }

    override fun toggleFlight(player: PlayerId): Boolean? {
        if (player !in roster) return null
        val next = !isFlying(player)
        flying[player] = next
        return next
    }

    override fun kick(player: PlayerId, reason: String): Boolean {
        if (roster.remove(player) == null) return false
        kicks += player to reason
        return true
    }
}
