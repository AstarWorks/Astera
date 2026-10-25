package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.event.PlayerProtectionExpired
import com.astarworks.astera.core.domain.event.PlayerProtectionGranted
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ISpawnProtectionGateway] (Wave 5o, ADR-0081).
 *
 * Tracks each player's protection expiry as an absolute server tick. When
 * [grantProtection] is called, schedules a one-shot task at
 * `currentTick + durationSeconds * 20` that revokes the protection (which
 * fires [PlayerProtectionExpired]). Subsequent grants while protection is
 * already active extend to the larger remaining window — the cheapest
 * "respawn-during-protection" handling that the Annihilation flow needs.
 *
 * Thread-safe (CHM-backed); the per-player TaskHandle is retained so a
 * second `grant` can cancel the stale expiry before scheduling the new one.
 */
public class InMemorySpawnProtectionGateway(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : ISpawnProtectionGateway {

    private data class Entry(
        val expiresAtTick: Long,
        val handle: IScheduler.TaskHandle,
    )

    private val active: MutableMap<PlayerId, Entry> = ConcurrentHashMap()

    override fun grantProtection(playerId: PlayerId, durationSeconds: Int) {
        if (durationSeconds <= 0) return

        val nowTick = scheduler.currentTick()
        val newExpiry = nowTick + durationSeconds.toLong() * TICKS_PER_SECOND
        val effectiveExpiry = active[playerId]?.let { maxOf(it.expiresAtTick, newExpiry) } ?: newExpiry

        // Cancel any pending expiry; we re-schedule below using the effective tick.
        active[playerId]?.handle?.cancel()

        val ticksUntilExpiry = (effectiveExpiry - nowTick).coerceAtLeast(1L)
        val handle = scheduler.runLaterTicks(ticksUntilExpiry) {
            // Only fire expiry if this entry is still the live one — guards
            // against late-firing tasks racing a more recent grantProtection.
            val current = active[playerId]
            if (current?.expiresAtTick == effectiveExpiry) {
                active.remove(playerId)
                eventBus.publish(
                    PlayerProtectionExpired(
                        playerId = playerId,
                        atTick = scheduler.currentTick(),
                    ),
                )
            }
        }
        active[playerId] = Entry(effectiveExpiry, handle)

        eventBus.publish(
            PlayerProtectionGranted(
                playerId = playerId,
                durationSeconds = durationSeconds,
                atTick = nowTick,
            ),
        )
    }

    override fun isProtected(playerId: PlayerId): Boolean {
        val entry = active[playerId] ?: return false
        return scheduler.currentTick() < entry.expiresAtTick
    }

    override fun revokeProtection(playerId: PlayerId) {
        val removed = active.remove(playerId) ?: return
        removed.handle.cancel()
        eventBus.publish(
            PlayerProtectionExpired(
                playerId = playerId,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    private companion object {
        const val TICKS_PER_SECOND: Long = 20L
    }
}
