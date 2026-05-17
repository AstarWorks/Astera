package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [ICooldownTracker] for tests.
 *
 * Stores actual (start, duration) state so that [isCoolingDown] and
 * [remainingTicks] reflect real cooldown logic based on the virtual tick
 * ([currentTick]). Advance [currentTick] to simulate time passing.
 *
 * [started] records all [start] calls for assertion.
 */
public class FakeCooldownTracker : ICooldownTracker {

    /** Virtual current tick — advance in tests to simulate passage of time. */
    public var currentTick: Long = 0L

    /** Key → absolute expiry tick. */
    private val expiresAt: MutableMap<Pair<PlayerId, CooldownKey>, Long> = mutableMapOf()

    /** Records of (playerId, key, durationTicks) passed to [start]. */
    public val started: MutableList<Triple<PlayerId, CooldownKey, Long>> = mutableListOf()

    /** Records of [tick] call arguments. */
    public val ticked: MutableList<Long> = mutableListOf()

    override fun isCoolingDown(playerId: PlayerId, key: CooldownKey): Boolean =
        remainingTicks(playerId, key) > 0L

    override fun remainingTicks(playerId: PlayerId, key: CooldownKey): Long {
        val expires = expiresAt[Pair(playerId, key)] ?: return 0L
        return (expires - currentTick).coerceAtLeast(0L)
    }

    override fun start(playerId: PlayerId, key: CooldownKey, durationTicks: Long) {
        started += Triple(playerId, key, durationTicks)
        expiresAt[Pair(playerId, key)] = currentTick + durationTicks
    }

    override fun reset(playerId: PlayerId, key: CooldownKey) {
        expiresAt.remove(Pair(playerId, key))
    }

    override fun tick(currentTick: Long): Set<Pair<PlayerId, CooldownKey>> {
        ticked += currentTick
        val expired = expiresAt.entries
            .filter { it.value <= currentTick }
            .map { it.key }
            .toSet()
        expired.forEach { expiresAt.remove(it) }
        return expired
    }

    override fun activeKeysFor(playerId: PlayerId): Set<CooldownKey> =
        expiresAt.entries
            .filter { (k, v) -> k.first == playerId && v > currentTick }
            .map { it.key.second }
            .toSet()
}
