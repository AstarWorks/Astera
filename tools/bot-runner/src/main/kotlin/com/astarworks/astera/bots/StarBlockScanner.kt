package com.astarworks.astera.bots

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Finds the blocks that pay a star when broken (ADR-0318 `star_blocks`: the server pays for ANY break of a listed material
 * during an economy match, no cooldown and no placed-block filter, bounded only by the match's block star bucket; the star
 * goes straight to the breaker, no ground item). Only the three that need no tool are scanned: barrel, chest, crafting table.
 *
 * The scan is incremental ([CELLS_PER_TICK] terrain lookups per tick), deterministic (fixed cell order) and repeated every
 * [RESCAN_MS]. Positions the bot gave up on (or dug [MAX_ATTEMPTS] times) are remembered so an air block is never retried.
 */
class StarBlockScanner(private val terrain: TerrainView) {
    private var found = emptyList<Triple<Int, Int, Int>>()
    private var pending = ArrayList<Triple<Int, Int, Int>>()
    private var cursor = -1
    private var cx = 0
    private var cy = 0
    private var cz = 0
    private var nextScanAt = 0L
    private val attempts = HashMap<Long, Int>()
    private val done = HashSet<Long>()

    /** Advances the scan by one tick's budget around ([x], [y], [z]); a finished scan sleeps for [RESCAN_MS]. */
    fun tick(now: Long, x: Double, y: Double, z: Double) {
        if (cursor < 0) {
            if (now < nextScanAt) return
            cx = floor(x).toInt(); cy = floor(y).toInt(); cz = floor(z).toInt()
            cursor = 0
            pending = ArrayList()
        }
        var budget = CELLS_PER_TICK
        while (budget-- > 0 && cursor < TOTAL) {
            val i = cursor++
            val bx = cx + i % SIDE - RADIUS
            val bz = cz + i / SIDE % SIDE - RADIUS
            val by = cy + i / (SIDE * SIDE) - DOWN
            if (key(bx, by, bz) in done) continue
            if (terrain.blockName(bx, by, bz) in PAYING) pending += Triple(bx, by, bz)
        }
        if (cursor >= TOTAL) {
            found = pending
            cursor = -1
            nextScanAt = now + RESCAN_MS
        }
    }

    /** The [rank]-th nearest (0 = nearest; teams spread over the blocks by slot, clamped to the farthest) paying block within [RADIUS] of the bot that is still there and not given up on; null = none. */
    fun nearest(x: Double, y: Double, z: Double, rank: Int = 0): Triple<Int, Int, Int>? {
        val ranked = ArrayList<Pair<Double, Triple<Int, Int, Int>>>()
        for (p in found) {
            if (key(p.first, p.second, p.third) in done) continue
            val d = hypot(p.first + 0.5 - x, p.third + 0.5 - z) + abs(p.second - y) * 0.5
            if (d > RADIUS) continue
            if (terrain.blockName(p.first, p.second, p.third) in PAYING) ranked += d to p
        }
        if (ranked.isEmpty()) return null
        ranked.sortBy { it.first }
        return ranked[rank.coerceIn(0, ranked.lastIndex)].second
    }

    /** One dig was issued on [p]; after [MAX_ATTEMPTS] digs the block counts as unbreakable and is dropped. */
    fun noteDig(p: Triple<Int, Int, Int>) {
        val k = key(p.first, p.second, p.third)
        val n = (attempts[k] ?: 0) + 1
        attempts[k] = n
        if (n >= MAX_ATTEMPTS) done += k
    }

    /** [p] cannot be reached or is gone: never target it again. */
    fun giveUp(p: Triple<Int, Int, Int>) { done += key(p.first, p.second, p.third) }

    private fun key(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

    companion object {
        val PAYING = setOf("minecraft:barrel", "minecraft:chest", "minecraft:crafting_table")
        const val RADIUS = 12
        const val DOWN = 4
        const val UP = 4
        private const val SIDE = RADIUS * 2 + 1
        private const val TOTAL = SIDE * SIDE * (DOWN + UP + 1)
        const val CELLS_PER_TICK = 450
        const val RESCAN_MS = 6_000L
        const val MAX_ATTEMPTS = 3
    }
}
