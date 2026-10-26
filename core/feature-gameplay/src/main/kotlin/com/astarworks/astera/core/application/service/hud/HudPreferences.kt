package com.astarworks.astera.core.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * UI-03 - the per-player HUD / notification toggles ([PlayerSettings.hpLineVisible] etc.) as hot-path
 * lookups. The status line asks every 2 ticks per player, so reads go through a short-lived cache
 * instead of the (possibly Postgres) settings repository; the settings screen calls [invalidate] after a
 * toggle so the change shows on the next tick, any other writer is picked up within [TTL_MS].
 */
public class HudPreferences(
    private val repository: () -> IPlayerSettingsRepository?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val settings: PlayerSettings, val readAtMs: Long)

    private val cache = ConcurrentHashMap<PlayerId, Entry>()

    public fun settings(playerId: PlayerId): PlayerSettings {
        val now = nowMs()
        cache[playerId]?.takeIf { now - it.readAtMs < TTL_MS }?.let { return it.settings }
        val read = repository()?.findOrDefault(playerId) ?: PlayerSettings.default(playerId)
        cache[playerId] = Entry(read, now)
        return read
    }

    public fun hpLineVisible(playerId: PlayerId): Boolean = settings(playerId).hpLineVisible

    public fun damageNumbersVisible(playerId: PlayerId): Boolean = settings(playerId).damageNumbersVisible

    public fun tutorialHintsEnabled(playerId: PlayerId): Boolean = settings(playerId).tutorialHintsEnabled

    public fun invalidate(playerId: PlayerId) {
        cache.remove(playerId)
    }

    public companion object {
        public const val TTL_MS: Long = 2_000L
    }
}
