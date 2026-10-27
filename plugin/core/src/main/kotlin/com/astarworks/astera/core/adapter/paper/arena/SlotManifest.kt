package com.astarworks.astera.core.adapter.paper.arena

import java.nio.file.Files
import java.nio.file.Path

/**
 * Which slot boxes hold arena blocks on disk. The allocator is in-memory, so after a restart / reload / crash the
 * world still contains the old arenas; this file lets the next boot sweep them out before pasting.
 * Line format: `index minX maxX minY maxY minZ maxZ`.
 */
internal class SlotManifest(private val file: Path) {
    @Synchronized fun load(): Map<Int, ArenaPaster.Box> = runCatching {
        if (!Files.exists(file)) emptyMap() else Files.readAllLines(file).mapNotNull { line ->
            val p = line.trim().split(' ').mapNotNull(String::toIntOrNull)
            if (p.size == 7) p[0] to ArenaPaster.Box(p[1], p[2], p[3], p[4], p[5], p[6]) else null
        }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized fun add(index: Int, box: ArenaPaster.Box) = save(load() + (index to box))
    @Synchronized fun remove(index: Int) { val current = load(); if (index in current) save(current - index) }

    private fun save(entries: Map<Int, ArenaPaster.Box>) = runCatching {
        Files.createDirectories(file.parent)
        Files.write(file, entries.map { (i, b) -> "$i ${b.minX} ${b.maxX} ${b.minY} ${b.maxY} ${b.minZ} ${b.maxZ}" })
    }
}
