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

    fun paste(world: World, plan: ArenaBuildPlan): CompletableFuture<Result<Journal, Error>> =
        write(world, plan.blocks, Journal(), capture = true)

    fun rollback(world: World, journal: Journal): CompletableFuture<Result<Journal, Error>> =
        write(world, journal.before.mapValues { it.value.asString }, Journal(), capture = false)

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
            val cache = HashMap<String, BlockData>()
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
                                val wanted = cache.getOrPut(state) { plugin.server.createBlockData(state) }
                                val block = world.getBlockAt(pos.x, pos.y, pos.z)
                                if (capture) journal.before.putIfAbsent(pos, block.blockData.clone())
                                block.setBlockData(wanted, false)
                            }
                            if (cursor < entries.size) schedule(TICK)
                            else if (pending.decrementAndGet() == 0) result.complete(Result.success(journal))
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
