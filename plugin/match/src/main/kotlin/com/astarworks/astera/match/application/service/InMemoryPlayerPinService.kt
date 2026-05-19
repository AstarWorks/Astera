package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IPlayerPinService
import com.astarworks.astera.match.domain.event.PlayerPinExpired
import com.astarworks.astera.match.domain.event.PlayerPinPlaced
import com.astarworks.astera.match.domain.pin.PlayerPin
import com.astarworks.astera.match.domain.pin.PlayerPinId

/**
 * Default in-memory [IPlayerPinService] (Wave 5e).
 *
 * Pins are match-scoped; no persistence is required because each pin
 * carries [PlayerPin.lifetimeTicks] and the unified scheduler advances
 * the clock via [tick]. The service publishes
 * [PlayerPinPlaced] on registration and [PlayerPinExpired] on either
 * lifetime exhaustion or explicit [expire].
 *
 * **Not thread-safe** — same Astera invariant as other match in-memory
 * services: all match-state mutation runs on the server main thread
 * (per `IScheduler` regional contract).
 */
public class InMemoryPlayerPinService(
    private val eventBus: IDomainEventBus,
) : IPlayerPinService {

    private data class PinRuntime(
        val pin: PlayerPin,
        var remainingTicks: Long,
    )

    private val byMatch: MutableMap<MatchId, MutableMap<PlayerPinId, PinRuntime>> =
        mutableMapOf()

    override fun place(pin: PlayerPin) {
        val perMatch = byMatch.getOrPut(pin.matchId) { mutableMapOf() }
        perMatch[pin.id] = PinRuntime(pin = pin, remainingTicks = pin.lifetimeTicks)
        eventBus.publish(PlayerPinPlaced(pin))
    }

    override fun expire(pinId: PlayerPinId) {
        // Pins are addressable by id alone; scan all matches (small N — at
        // most a few hundred live pins server-wide in Phase 1).
        val iter = byMatch.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.remove(pinId) != null) {
                if (entry.value.isEmpty()) iter.remove()
                eventBus.publish(PlayerPinExpired(pinId))
                return
            }
        }
    }

    override fun currentPins(matchId: MatchId): List<PlayerPin> =
        byMatch[matchId]?.values?.map { it.pin }.orEmpty()

    override fun tick(matchId: MatchId, elapsedTicks: Long) {
        require(elapsedTicks >= 0) { "elapsedTicks must be >= 0: $elapsedTicks" }
        val pins = byMatch[matchId] ?: return
        if (elapsedTicks == 0L) return

        // Collect first to avoid concurrent modification while we call expire().
        val toExpire = mutableListOf<PlayerPinId>()
        for ((id, runtime) in pins) {
            runtime.remainingTicks -= elapsedTicks
            if (runtime.remainingTicks <= 0L) toExpire += id
        }
        for (id in toExpire) expire(id)
    }
}
