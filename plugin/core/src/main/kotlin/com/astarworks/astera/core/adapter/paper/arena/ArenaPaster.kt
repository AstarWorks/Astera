package com.astarworks.astera.core.adapter.paper.arena

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.TICK
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** Chunk-owned, bounded writes. Futures always complete with a value, including scheduler retirement. */
internal class ArenaPaster(
    private val plugin: JavaPlugin,
    private val scheduler: IScheduler,
    private val blocksPerTick: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    sealed interface Error {
        data class WriteFailed(val detail: String) : Error
        data object Retired : Error
    }
    class Journal {
        val before = ConcurrentHashMap<ArenaBlockPos, BlockData>()
    }

    // ponytail: a server-wide cap is stricter than a per-region cap and also covers merged regions.
    // Split into region identities only if warm-pool throughput needs it.
    private var budgetTick = Long.MIN_VALUE
    private var remaining = 0
    @Synchronized private fun allowance(): Int {
        val tick = scheduler.currentTick()
        if (budgetTick != tick) { budgetTick = tick; remaining = blocksPerTick }
        val granted = minOf(remaining, 256)
        remaining -= granted
        return granted
    }

    /** The caller owns [journal] so a failed paste can still be rolled back from what it captured before failing. */
    fun paste(world: World, plan: ArenaBuildPlan, journal: Journal = Journal()): CompletableFuture<Result<Journal, Error>> =
        write(world, plan.blocks, journal, capture = true)

    fun rollback(world: World, journal: Journal): CompletableFuture<Result<Journal, Error>> =
        write(world, journal.before.mapValues { it.value.asString }, Journal(), capture = false)

    /** Inclusive block box; used to sweep what an earlier run left behind in a slot (server restart, plugin reload). */
    data class Box(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val minZ: Int, val maxZ: Int)

    /** Sets every non-air block of [box] to air, chunk by chunk under the same per-tick budget as a paste. */
    fun clear(world: World, box: Box): CompletableFuture<Result<Unit, Error>> {
        val result = CompletableFuture<Result<Unit, Error>>()
        val chunks = ((box.minX shr 4)..(box.maxX shr 4)).flatMap { cx -> ((box.minZ shr 4)..(box.maxZ shr 4)).map { cz -> cx to cz } }
        val pending = AtomicInteger(chunks.size)
        val air = plugin.server.createBlockData("minecraft:air")
        val height = box.maxY - box.minY + 1
        for ((cx, cz) in chunks) {
            val x0 = maxOf(box.minX, cx shl 4); val x1 = minOf(box.maxX, (cx shl 4) + 15)
            val z0 = maxOf(box.minZ, cz shl 4); val z1 = minOf(box.maxZ, (cz shl 4) + 15)
            val width = x1 - x0 + 1
            val total = width * (z1 - z0 + 1) * height
            var cursor = 0
            val scope = ExecutionScope.Region(WorldId(world.name), cx, cz)
            fun schedule(delay: Duration) {
                if (result.isDone) return
                runCatching {
                    scheduler.run(scope, delay) {
                        if (result.isDone) return@run
                        runCatching {
                            // Air scanning is cheap; only real writes spend the budget.
                            var writes = allowance()
                            var scanned = 0
                            while (cursor < total && writes > 0 && scanned < 65_536) {
                                val i = cursor++; scanned++
                                val y = box.minY + i % height
                                val rest = i / height
                                val block = world.getBlockAt(x0 + rest % width, y, z0 + rest / width)
                                if (!block.type.isAir) { block.setBlockData(air, false); writes-- }
                            }
                            if (cursor < total) schedule(TICK)
                            else {
                                world.getChunkAt(cx, cz).entities.filter(::isArenaDebris).forEach { it.remove() }
                                if (pending.decrementAndGet() == 0) result.complete(Result.success(Unit))
                            }
                        }.onFailure { result.complete(Result.failure(Error.WriteFailed(it.message ?: it.javaClass.simpleName))) }
                    }.onRetired { result.complete(Result.failure(Error.Retired)) }
                }.onFailure { result.complete(Result.failure(Error.WriteFailed(it.message ?: it.javaClass.simpleName))) }
            }
            world.getChunkAtAsync(cx, cz, true).whenComplete { _, failure ->
                if (failure != null) result.complete(Result.failure(Error.WriteFailed(failure.message ?: "chunk load failed")))
                else schedule(Duration.ZERO)
            }
        }
        return result
    }

    /** Drops, arrows and orbs a match leaves lying around; mobs, displays and players are owned by their own cleanup. */
    private fun isArenaDebris(e: org.bukkit.entity.Entity): Boolean =
        e is org.bukkit.entity.Item || e is org.bukkit.entity.Projectile || e is org.bukkit.entity.ExperienceOrb

    private fun write(
        world: World,
        blocks: Map<ArenaBlockPos, String>,
        journal: Journal,
        capture: Boolean,
    ): CompletableFuture<Result<Journal, Error>> {
        val result = CompletableFuture<Result<Journal, Error>>()
        val chunks = blocks.entries.groupBy { (it.key.x shr 4) to (it.key.z shr 4) }
        if (chunks.isEmpty()) { result.complete(Result.success(journal)); return result }
        val pending = AtomicInteger(chunks.size)
        for ((chunk, entries) in chunks) {
            val scope = ExecutionScope.Region(WorldId(world.name), chunk.first, chunk.second)
            val cache = HashMap<String, BlockData?>()
            var cursor = 0
            fun schedule(delay: Duration) {
                if (result.isDone) return
                runCatching {
                    scheduler.run(scope, delay) {
                        if (result.isDone) return@run
                        runCatching {
                            val end = minOf(entries.size, cursor + allowance())
                            while (cursor < end) {
                                val (pos, state) = entries[cursor++]
                                // An unparseable block-data string must not abort the whole paste (it once left the lobby
                                // without NPCs/holograms): skip just that block and say so loudly.
                                val wanted = if (state in cache) cache[state] else runCatching { plugin.server.createBlockData(state) }
                                    .onFailure { log.error("Invalid block data '{}' at ({}, {}, {}) in world '{}': {}; block skipped",
                                        state, pos.x, pos.y, pos.z, world.name, it.message) }
                                    .getOrNull().also { cache[state] = it }
                                if (wanted == null) continue
                                val block = world.getBlockAt(pos.x, pos.y, pos.z)
                                if (capture) journal.before.putIfAbsent(pos, block.blockData.clone())
                                block.setBlockData(wanted, false)
                            }
                            if (cursor < entries.size) schedule(TICK)
                            else {
                                if (!capture) world.getChunkAt(chunk.first, chunk.second).entities.filter(::isArenaDebris).forEach { it.remove() }
                                if (pending.decrementAndGet() == 0) result.complete(Result.success(journal))
                            }
                        }.onFailure { result.complete(Result.failure(Error.WriteFailed(it.message ?: it.javaClass.simpleName))) }
                    }.onRetired { result.complete(Result.failure(Error.Retired)) }
                }.onFailure { result.complete(Result.failure(Error.WriteFailed(it.message ?: it.javaClass.simpleName))) }
            }
            world.getChunkAtAsync(chunk.first, chunk.second, true).whenComplete { _, failure ->
                if (failure != null) result.complete(Result.failure(Error.WriteFailed(failure.message ?: "chunk load failed")))
                else schedule(Duration.ZERO)
            }
        }
        return result
    }
}
