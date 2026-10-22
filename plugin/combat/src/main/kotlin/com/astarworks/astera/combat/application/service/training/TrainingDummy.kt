package com.astarworks.astera.combat.application.service.training

import com.astarworks.astera.core.domain.lobby.TrainingDummySpot
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.ArrayDeque

/**
 * One lobby training dummy (F3-11, ADR-0200): HP, rolling DPS and idle regeneration. Pure state driven by
 * ticks, no host types. A dummy never dies: HP bottoms out at [MIN_HP] (so targeting never skips it as
 * dead), and it refills to [maxHp] after [REGEN_IDLE_TICKS] without a hit.
 */
public class TrainingDummy(
    public val spot: TrainingDummySpot,
    public val maxHp: Double = MAX_HP,
) {
    private data class Hit(val tick: Long, val amount: Double)

    private val hits = ArrayDeque<Hit>()

    /** Entity id of the body while it exists in the world. */
    public var bodyId: PlayerId? = null

    public var hp: Double = maxHp
        private set

    /** Tick of the last hit, or `null` after a regeneration / before the first hit. */
    public var lastHitTick: Long? = null
        private set

    /** Label text last pushed to the body, to skip redundant updates. */
    internal var shownLabel: List<String>? = null

    /** Applies [amount] (> 0) at [tick]; returns the HP left (never below [MIN_HP]). */
    public fun hit(amount: Double, tick: Long): Double {
        require(amount > 0.0) { "hit amount must be > 0: $amount" }
        hp = (hp - amount).coerceAtLeast(MIN_HP)
        lastHitTick = tick
        hits.addLast(Hit(tick, amount))
        trim(tick)
        return hp
    }

    /** Damage dealt per second over the last [DPS_WINDOW_TICKS] ticks. */
    public fun dps(now: Long): Double {
        trim(now)
        return hits.sumOf { it.amount } / (DPS_WINDOW_TICKS / TICKS_PER_SECOND)
    }

    /** Refills after the idle time; returns whether anything changed. */
    public fun regenerate(now: Long): Boolean {
        val last = lastHitTick ?: return false
        if (now - last < REGEN_IDLE_TICKS) return false
        hp = maxHp
        lastHitTick = null
        hits.clear()
        return true
    }

    /** Heals up to [amount] (a Heal mechanic on a dummy), clamped to [maxHp]. */
    public fun heal(amount: Double) {
        hp = (hp + amount).coerceAtMost(maxHp)
    }

    private fun trim(now: Long) {
        while (hits.isNotEmpty() && now - hits.first.tick >= DPS_WINDOW_TICKS) hits.removeFirst()
    }

    public companion object {
        public const val MAX_HP: Double = 500.0
        public const val MIN_HP: Double = 1.0

        /** Idle time before a dummy refills: 3 s. */
        public const val REGEN_IDLE_TICKS: Long = 60L

        /** Rolling DPS window: 5 s. */
        public const val DPS_WINDOW_TICKS: Long = 100L
        private const val TICKS_PER_SECOND: Double = 20.0
    }
}

/** All dummies of the range, addressable by spot id and by body id. */
public class TrainingDummyRegistry {
    private val bySpot = LinkedHashMap<String, TrainingDummy>()
    private val byBody = HashMap<PlayerId, TrainingDummy>()

    public fun register(dummy: TrainingDummy) {
        bySpot[dummy.spot.id] = dummy
    }

    public fun all(): List<TrainingDummy> = bySpot.values.toList()

    public fun forSpot(spotId: String): TrainingDummy? = bySpot[spotId]

    public fun forBody(id: PlayerId): TrainingDummy? = byBody[id]

    public fun isDummy(id: PlayerId): Boolean = byBody.containsKey(id)

    /** Associates [dummy] with the freshly spawned body [id] (drops any previous association). */
    public fun bind(dummy: TrainingDummy, id: PlayerId) {
        unbind(dummy)
        dummy.bodyId = id
        byBody[id] = dummy
    }

    public fun unbind(dummy: TrainingDummy) {
        dummy.bodyId?.let { byBody.remove(it) }
        dummy.bodyId = null
        dummy.shownLabel = null
    }

    public fun clear() {
        bySpot.clear()
        byBody.clear()
    }
}
