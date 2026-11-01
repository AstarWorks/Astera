package com.astarworks.astera.bots

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Block-state id -> namespaced block name (`minecraft:chest`, no properties) for the server protocol 26.3.
 *
 * MCProtocolLib 26.3 ships no block registry (its jar holds packet/codec classes only), so the table is a resource,
 * `block-states-26.3.txt`: one line per block, `<name> <firstStateId> <stateCount>`, sorted by first id. The state ids
 * of one block are contiguous in the vanilla registry. It is generated from the Paper 26.3 data report:
 * `java -DbundlerMainClass=net.minecraft.data.Main -jar paper.jar --reports --output out`, then convert
 * `out/reports/blocks.json` (`name -> {states: [{id, ...}]}`) to `name min(id) count(ids)` lines sorted by min id.
 * Regenerate whenever `mcprotocollib` in `gradle/libs.versions.toml` moves to a new game version.
 */
object BlockStateNames {
    private val log = LoggerFactory.getLogger(BlockStateNames::class.java)
    private val warned = AtomicBoolean(false)

    private class Table(val names: Array<String>, val first: IntArray, val count: IntArray)

    private val table: Table by lazy {
        val stream = BlockStateNames::class.java.getResourceAsStream("/block-states-26.3.txt")
            ?: error("block-states-26.3.txt missing from the bot-runner resources")
        val rows = stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map {
                val p = it.split(' ')
                Triple(p[0], p[1].toInt(), p[2].toInt())
            }.toList()
        }
        Table(rows.map { it.first }.toTypedArray(), rows.map { it.second }.toIntArray(), rows.map { it.third }.toIntArray())
    }

    /** Block name of [stateId]; an id outside the registry warns once and answers `null`. */
    fun nameOf(stateId: Int): String? {
        val t = table
        var lo = 0
        var hi = t.first.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                stateId < t.first[mid] -> hi = mid - 1
                stateId >= t.first[mid] + t.count[mid] -> lo = mid + 1
                else -> return t.names[mid]
            }
        }
        if (warned.compareAndSet(false, true)) log.warn("unknown block state id {} (table is for protocol 26.3); answering null", stateId)
        return null
    }
}
