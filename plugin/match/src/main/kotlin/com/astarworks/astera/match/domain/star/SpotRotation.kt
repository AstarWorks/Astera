package com.astarworks.astera.match.domain.star

import kotlin.random.Random

/**
 * Which candidate spot a timer generator pops at next (ADR-0318): a seeded shuffle of all spots, cycle after cycle, so
 * every spot is used before any repeats and the same spot is never picked twice in a row (with two or more spots).
 * Deterministic for a given seed, which is what the game-sim relies on.
 */
public class SpotRotation(private val size: Int, seed: Long) {
    private val random = Random(seed)
    private val queue = ArrayDeque<Int>()
    private var last: Int = -1

    init {
        require(size >= 1) { "a rotation needs at least one spot" }
    }

    /**
     * The next spot index for which [isFree] holds, or `null` when no free spot other than the last one is left (the last
     * spot is never returned twice in a row, even when it is the only free one). Skipped occupied spots are consumed.
     */
    public fun next(isFree: (Int) -> Boolean): Int? {
        val allowed = { i: Int -> isFree(i) && (size == 1 || i != last) }
        if ((0 until size).none(allowed)) return null
        while (true) {
            val candidate = take()
            if (allowed(candidate)) { last = candidate; return candidate }
        }
    }

    /** Whether [next] would return a spot (checked before the budget is charged for the pop). */
    public fun hasNext(isFree: (Int) -> Boolean): Boolean = (0 until size).any { isFree(it) && (size == 1 || it != last) }

    private fun take(): Int {
        if (queue.isEmpty()) {
            val cycle = (0 until size).shuffled(random).toMutableList()
            if (size > 1 && cycle.first() == last) cycle.add(cycle.removeAt(0))
            queue.addAll(cycle)
        }
        return queue.removeFirst()
    }
}
