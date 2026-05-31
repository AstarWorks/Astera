package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.hud.TabListEntry
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * On every [PlayerJoinedServer] / [PlayerQuitServer], refresh every online
 * player's tab list with the current roster (ADR-0138 — Wave AB
 * 5lobby-ux-complete).
 *
 * # Context
 *
 * Round 18 audit α verified `ITabList` port exists
 * (`plugin/core/.../adapter/api/hud/ITabList.kt`) and `InMemoryTabList`
 * is registered at priority 0 in `AsteraCorePlugin.onEnable`, but **no
 * listener populated entries on player join/quit**. The tab list remained
 * vanilla Minecraft default (UUID-keyed username, no per-player ping /
 * rank). This listener closes the gap with a roster-wide refresh whenever
 * the online set changes.
 *
 * # Roster source
 *
 * Reads `IPlayerSessionRepository.all()` to get every active session
 * (any `SessionState` except `Disconnected`), then resolves each
 * `PlayerId` to a display name via `IPlayerProfileService.find`. Players
 * without a profile fall back to a short UUID prefix.
 *
 * # Ping placeholder
 *
 * Phase 1 PoC has no `IPlayerPingProvider` port. Every `TabListEntry.pingMs`
 * is set to `0` (the contract permits non-negative ints; 0 displays as
 * "no signal" via the vanilla 5-bar icon). Wave 6b's `PaperPingProvider`
 * will populate from Bukkit `Player.getPing()` and a refactor of this
 * listener will inject the port.
 *
 * # Refresh strategy
 *
 * Per [ITabList.setPlayerEntries] contract, calling `setPlayerEntries`
 * replaces the per-player entry list atomically. The listener iterates
 * every online player and calls `setPlayerEntries(player, currentRoster)`
 * — O(N²) for N online players, but Phase 1 PoC servers cap at ~100
 * concurrent so the cost is bounded.
 *
 * # Failure handling
 *
 * `ITabList.setPlayerEntries` is best-effort; the InMemory impl never
 * throws, and Wave 6b Paper impl will swallow Bukkit packet-write
 * failures internally. No try/catch needed at this layer.
 */
public class UpdateTabListOnPlayerJoinedListener(
    private val tabList: ITabList,
    private val sessions: IPlayerSessionRepository,
    private val profileService: IPlayerProfileService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: DomainEvent) {
        when (event) {
            is PlayerJoinedServer, is PlayerQuitServer -> refreshAll()
            else -> {
                log.warn(
                    "UpdateTabListOnPlayerJoinedListener received unexpected event type {}",
                    event::class.simpleName,
                )
            }
        }
    }

    private fun refreshAll() {
        val roster = currentRoster()
        for (sessionPlayerId in sessions.all().map { it.playerId }) {
            tabList.setPlayerEntries(sessionPlayerId, roster)
        }
    }

    private fun currentRoster(): List<TabListEntry> =
        sessions.all().map { session ->
            val name = profileService.find(session.playerId)?.displayName
                ?: session.playerId.uuid.toString().take(8)
            TabListEntry(
                name = name,
                displayName = null,
                pingMs = 0,
            )
        }
}
