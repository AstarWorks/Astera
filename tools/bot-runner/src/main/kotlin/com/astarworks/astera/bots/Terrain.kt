package com.astarworks.astera.bots

import io.netty.buffer.Unpooled
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection
import java.util.concurrent.ConcurrentHashMap

/**
 * The blocks a bot knows about, rebuilt from the chunk and block-update packets it receives, so the combat AI can
 * look for ground before it steps (the arenas are floating sky islands: one step too far is a fall into the void).
 *
 * Only "air or not" matters: state id 0 is air in every registry version. A column that was never received answers
 * `null` (unknown), which callers treat as "do not walk there".
 */
class Terrain {
    private val chunks = ConcurrentHashMap<Long, Array<ChunkSection?>>()

    @Volatile private var minY = DEFAULT_MIN_Y

    fun clear() = chunks.clear()

    fun forget(chunkX: Int, chunkZ: Int) {
        chunks.remove(key(chunkX, chunkZ))
    }

    /** Decodes the section list of one chunk packet. A malformed chunk is dropped, never thrown. */
    fun load(chunkX: Int, chunkZ: Int, data: ByteArray) {
        val sections = ArrayList<ChunkSection?>()
        val buf = Unpooled.wrappedBuffer(data)
        try {
            while (buf.isReadable) sections += MinecraftTypes.readChunkSection(buf, BLOCK_STATES, BIOMES)
        } catch (_: Throwable) {
            // keep whatever was readable; the rest of the column stays unknown
        } finally {
            buf.release()
        }
        // Overworld-like dimensions: 24 sections start at y=-64, 16 sections at y=0.
        minY = if (sections.size == 16) 0 else DEFAULT_MIN_Y
        chunks[key(chunkX, chunkZ)] = sections.toTypedArray()
    }

    fun set(x: Int, y: Int, z: Int, state: Int) {
        val sections = chunks[key(x shr 4, z shr 4)] ?: return
        val index = (y - minY) shr 4
        val section = sections.getOrNull(index) ?: return
        section.setBlock(x and 15, (y - minY) and 15, z and 15, state)
    }

    /** Block state id at the position, or `null` when its chunk/section is not known. */
    fun stateAt(x: Int, y: Int, z: Int): Int? {
        val sections = chunks[key(x shr 4, z shr 4)] ?: return null
        val index = (y - minY) shr 4
        if (index < 0) return 0
        val section = sections.getOrNull(index) ?: return if (index >= sections.size) 0 else null
        return section.getBlock(x and 15, (y - minY) and 15, z and 15)
    }

    /** True when the chunk holding ([x], [z]) was received (a missing floor there means a real hole, not "unknown"). */
    fun knows(x: Double, z: Double): Boolean = chunks.containsKey(key(Math.floor(x).toInt() shr 4, Math.floor(z).toInt() shr 4))

    fun isSolid(x: Int, y: Int, z: Int): Boolean? = stateAt(x, y, z)?.let { it != AIR }

    /**
     * Feet height of the walkable floor in the column of ([x], [z]) within [maxStepUp] above / [maxDrop] below
     * [feetY]: the top of the highest solid block that has air above it. `null` = no floor (a hole or the edge of
     * the island) or the column is unknown.
     */
    fun floorAt(x: Double, z: Double, feetY: Double, maxStepUp: Int = 0, maxDrop: Int = 2): Double? {
        val bx = Math.floor(x).toInt()
        val bz = Math.floor(z).toInt()
        val top = Math.floor(feetY + 0.05).toInt() + maxStepUp
        val bottom = Math.floor(feetY + 0.05).toInt() - maxDrop
        for (y in top downTo bottom) {
            val solid = isSolid(bx, y - 1, bz) ?: return null
            if (!solid) continue
            // headroom: two free blocks above the floor
            if (isSolid(bx, y, bz) == true || isSolid(bx, y + 1, bz) == true) continue
            return y.toDouble()
        }
        return null
    }

    private fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

    private companion object {
        const val AIR = 0
        const val DEFAULT_MIN_Y = -64
        // Only used to size direct palettes when *writing*; reading never consults them.
        const val BLOCK_STATES = 32768
        const val BIOMES = 64
    }
}
