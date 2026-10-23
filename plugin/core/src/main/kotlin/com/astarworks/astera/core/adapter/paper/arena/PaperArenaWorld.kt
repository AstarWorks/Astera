package com.astarworks.astera.core.adapter.paper.arena

import com.astarworks.astera.core.application.port.outbound.IArenaBuilder
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.stage.ArenaIslandGenerator
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.block.Biome
import org.bukkit.block.data.BlockData
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.Random

/**
 * Builds the sky-island arenas (ADR-0246) of every stage that declares a layout into its void world
 * (`arenas`), the same way [com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyWorld] builds the lobby:
 *
 *  - the world is created on demand with an empty-chunk generator;
 *  - each stage's [ArenaBuildPlan] is pasted once. A marker file in the plugin data folder records, per stage,
 *    `(layout marker version, plan digest, bounds)`; when it matches (and a sample of the build is still
 *    there) nothing is placed, otherwise the old bounds are cleared and the new plan is pasted;
 *  - [repairLater] re-places blocks players destroyed during a match, leaving objective marker cells alone;
 *  - a build of more than [SYNC_BLOCK_LIMIT] blocks (the arenas are big islands) is spread over server ticks:
 *    clearing the old build and pasting the new one run in slices of at most [TICK_BUDGET_NS] per tick, so a boot
 *    never stalls. [ensureBuilt] (a match is about to start) finishes a pending build on the spot. The marker is
 *    only written once the paste is complete, so an interrupted build is redone on the next boot.
 *
 * Main thread only: calls from another thread are re-queued onto the main thread.
 */
internal class PaperArenaWorld(
    private val plugin: JavaPlugin,
    private val stages: () -> IStageRegistry?,
) : IArenaBuilder {

    private val log = LoggerFactory.getLogger(javaClass)
    private val server get() = plugin.server
    private val plans = HashMap<StageId, Pair<Stage, ArenaBuildPlan>>()
    private val repairs = HashSet<StageId>()

    /** Builds spread over ticks, in submission order; at most one per stage. */
    private val jobs = LinkedHashMap<StageId, Job>()
    private var pump: BukkitTask? = null

    /** Y below which a player is killed in [worldName], or `null` when no layout stage lives there. */
    fun killY(worldName: String): Int? =
        stages()?.all()?.mapNotNull { stage -> stage.layout?.takeIf { stage.world.value == worldName }?.killY }?.minOrNull()

    /** Builds every layout stage. Returns `(built or already built, stages with a layout)`. */
    fun installAll(): Pair<Int, Int> {
        val withLayout = stages()?.all()?.filter { it.layout != null }.orEmpty()
        return withLayout.count { build(it, urgent = false) } to withLayout.size
    }

    override fun ensureBuilt(stageId: StageId): Boolean {
        val stage = stages()?.find(stageId) ?: return true
        if (stage.layout == null) return true
        if (!server.isPrimaryThread) {
            server.scheduler.runTask(plugin, Runnable { build(stage, urgent = true) })
            return true
        }
        return build(stage, urgent = true)
    }

    override fun repairLater(stageId: StageId) {
        if (!repairs.add(stageId)) return
        server.scheduler.runTaskLater(
            plugin,
            Runnable {
                repairs.remove(stageId)
                runCatching { repair(stageId) }.onFailure { log.warn("Arena repair of '{}' failed: {}", stageId.value, it.message) }
            },
            REPAIR_DELAY_TICKS,
        )
    }

    // ── build ─────────────────────────────────────────────────────────────

    private fun planFor(stage: Stage): ArenaBuildPlan {
        plans[stage.id]?.let { (cachedStage, plan) -> if (cachedStage == stage) return plan }
        val plan = ArenaIslandGenerator.generate(stage)
        plan.problems.forEach { log.warn("Arena '{}': {}", stage.id.value, it) }
        plans[stage.id] = stage to plan
        return plan
    }

    /**
     * Builds [stage] unless it stands already. Small builds run now; a big one is queued onto the tick pump and this
     * returns `true` ("accepted") unless [urgent], in which case it is finished before returning.
     */
    private fun build(stage: Stage, urgent: Boolean): Boolean {
        val layout = stage.layout ?: return true
        return try {
            jobs[stage.id]?.let { pending ->
                if (urgent) finishNow(pending)
                return true
            }
            val world = worldOf(stage) ?: return false
            val t0 = System.nanoTime()
            val plan = planFor(stage)
            val planMs = (System.nanoTime() - t0) / 1_000_000
            val markers = readMarkers()
            val expected = Marker(layout.markerVersion, plan.digest(), plan.minX, plan.maxX, plan.minY, plan.maxY, plan.minZ, plan.maxZ)
            val previous = markers[stage.id.value]?.let(Marker::parse)
            if (previous == expected && sampleIntact(world, plan)) {
                log.info("Arena '{}' already built (marker {})", stage.id.value, layout.markerVersion)
                return true
            }
            // Only the previous build can have left blocks behind (a new one is pasted over its own area); without a
            // marker, clear the new bounds only when something is standing there.
            val clearBox = when {
                previous != null -> Box(previous.minX, previous.maxX, previous.minY, previous.maxY, previous.minZ, previous.maxZ)
                !sampleEmpty(world, plan) -> Box(plan.minX, plan.maxX, plan.minY, plan.maxY, plan.minZ, plan.maxZ)
                else -> null
            }
            val steps = sequence {
                if (clearBox != null) yieldAll(clearSteps(world, clearBox))
                yieldAll(pasteSteps(world, plan, onlyMissing = false, counter = null))
            }.iterator()
            val big = plan.blocks.size > SYNC_BLOCK_LIMIT
            val job = Job(stage.id, steps) { elapsedMs, ticks ->
                // Re-read: other stages' builds finish in between and each writes its own entry.
                val latest = readMarkers()
                latest[stage.id.value] = expected.toString()
                writeMarkers(latest)
                log.info(
                    "Arena '{}' built: {} blocks, {} islands in {} ms ({} ms generating the plan, {} tick(s){})",
                    stage.id.value, plan.blocks.size, layout.islands.size, elapsedMs + planMs, planMs, ticks,
                    if (clearBox != null) ", old build cleared" else "",
                )
            }
            if (big && !urgent) {
                log.info("Arena '{}': {} blocks, spreading the build over ticks", stage.id.value, plan.blocks.size)
                jobs[stage.id] = job
                startPump()
            } else {
                job.run(Long.MAX_VALUE)
                job.complete()
            }
            true
        } catch (t: Throwable) {
            jobs.remove(stage.id)
            log.error("Arena '{}' could not be built: {}", stage.id.value, t.message, t)
            false
        }
    }

    private fun repair(stageId: StageId) {
        val stage = stages()?.find(stageId) ?: return
        if (stage.layout == null || stageId in jobs) return
        val world = server.getWorld(stage.world.value) ?: return
        val plan = planFor(stage)
        val fixed = intArrayOf(0)
        val job = Job(stageId, pasteSteps(world, plan, onlyMissing = true, counter = fixed).iterator()) { ms, ticks ->
            if (fixed[0] > 0) log.info("Arena '{}' repaired: {} block(s) re-placed in {} ms ({} tick(s))", stageId.value, fixed[0], ms, ticks)
        }
        if (plan.blocks.size > SYNC_BLOCK_LIMIT) {
            jobs[stageId] = job
            startPump()
        } else {
            job.run(Long.MAX_VALUE)
            job.complete()
        }
    }

    // ── tick pump ─────────────────────────────────────────────────────────

    /** One build: [steps] does the work a slice at a time, [onDone] runs once everything was placed. */
    private class Job(
        val stageId: StageId,
        private val steps: Iterator<Unit>,
        private val onDone: (elapsedMs: Long, ticks: Int) -> Unit,
    ) {
        private val startNs = System.nanoTime()
        private var ticks = 0

        /** Runs steps until [deadlineNs]; `true` once none are left. */
        fun run(deadlineNs: Long): Boolean {
            ticks++
            var n = 0
            while (steps.hasNext()) {
                steps.next()
                if ((++n and 63) == 0 && System.nanoTime() >= deadlineNs) return !steps.hasNext()
            }
            return true
        }

        fun complete() = onDone((System.nanoTime() - startNs) / 1_000_000, ticks)
    }

    private fun finishNow(job: Job) {
        jobs.remove(job.stageId)
        job.run(Long.MAX_VALUE)
        job.complete()
    }

    private fun startPump() {
        if (pump != null) return
        pump = server.scheduler.runTaskTimer(plugin, Runnable { tickPump() }, 1L, 1L)
    }

    private fun tickPump() {
        val deadline = System.nanoTime() + TICK_BUDGET_NS
        val it = jobs.values.iterator()
        while (it.hasNext() && System.nanoTime() < deadline) {
            val job = it.next()
            try {
                if (job.run(deadline)) {
                    it.remove()
                    job.complete()
                }
            } catch (t: Throwable) {
                it.remove()
                log.error("Arena build of '{}' failed: {}", job.stageId.value, t.message, t)
            }
        }
        if (jobs.isEmpty()) {
            pump?.cancel()
            pump = null
        }
    }

    private fun worldOf(stage: Stage): World? {
        val name = stage.world.value
        server.getWorld(name)?.let { return it }
        val spawn = stage.spawnPoints.firstOrNull()?.position
        val created = server.createWorld(
            WorldCreator.name(name)
                .environment(World.Environment.NORMAL)
                .generator(VoidGenerator(spawn?.x ?: 0.0, (stage.layout?.surfaceY ?: 64) + 1.0, spawn?.z ?: 0.0))
                .biomeProvider(PlainsOnly)
                .generateStructures(false),
        )
        if (created == null) log.error("Arena world '{}' could not be created", name)
        else log.info("Arena world '{}' created (void)", name)
        return created
    }

    /** Places the plan one block per step; with [onlyMissing] only blocks that differ and are not objective cells. */
    private fun pasteSteps(world: World, plan: ArenaBuildPlan, onlyMissing: Boolean, counter: IntArray?): Sequence<Unit> = sequence {
        val cache = HashMap<String, BlockData?>()
        fun data(state: String): BlockData? = cache.getOrPut(state) {
            runCatching { server.createBlockData(state) }
                .onFailure { log.warn("Arena build: invalid block data '{}': {}", state, it.message) }
                .getOrNull()
        }
        for ((pos, state) in plan.blocks) {
            if (onlyMissing && pos in plan.volatileCells) continue
            val wanted = data(state) ?: continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (onlyMissing && block.blockData == wanted) continue
            block.setBlockData(wanted, false)
            if (counter != null) counter[0]++
            yield(Unit)
        }
    }

    private data class Box(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val minZ: Int, val maxZ: Int)

    /** Wipes [box] (plus a small margin) one column per step: the previous build may have been larger or elsewhere. */
    private fun clearSteps(world: World, box: Box): Sequence<Unit> = sequence {
        val air = server.createBlockData(Material.AIR)
        val minY = box.minY - 2
        val maxY = box.maxY + 12
        for (x in box.minX - 4..box.maxX + 4) {
            for (z in box.minZ - 4..box.maxZ + 4) {
                for (y in minY..maxY) {
                    val block = world.getBlockAt(x, y, z)
                    if (!block.type.isAir) block.setBlockData(air, false)
                }
                yield(Unit)
            }
        }
    }

    /** A few plan blocks are really there (guards against a wiped world with a surviving marker). */
    private fun sampleIntact(world: World, plan: ArenaBuildPlan): Boolean =
        sample(plan).all { (pos, state) -> world.getBlockAt(pos.x, pos.y, pos.z).type == Material.matchMaterial(state.substringBefore('[')) }

    private fun sampleEmpty(world: World, plan: ArenaBuildPlan): Boolean =
        sample(plan).all { (pos, _) -> world.getBlockAt(pos.x, pos.y, pos.z).type.isAir }

    private fun sample(plan: ArenaBuildPlan): List<Pair<ArenaBlockPos, String>> {
        val tops = plan.blocks.entries.filter { it.key.y == plan.surfaceY && it.key !in plan.volatileCells }
            .sortedWith(compareBy({ it.key.x }, { it.key.z }))
        if (tops.isEmpty()) return emptyList()
        return (0 until SAMPLES).map { tops[it * (tops.size - 1) / (SAMPLES - 1)] }.map { it.key to it.value }
    }

    // ── marker ────────────────────────────────────────────────────────────

    private data class Marker(
        val version: Int,
        val digest: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val minZ: Int,
        val maxZ: Int,
    ) {
        override fun toString(): String = "$version,$digest,$minX,$maxX,$minY,$maxY,$minZ,$maxZ"

        companion object {
            fun parse(text: String): Marker? {
                val p = text.split(',').mapNotNull { it.trim().toIntOrNull() }
                return if (p.size == 8) Marker(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]) else null
            }
        }
    }

    private fun markerPath(): Path = plugin.dataFolder.toPath().toAbsolutePath().resolve("arena-worlds.marker")

    private fun readMarkers(): MutableMap<String, String> {
        val path = markerPath()
        val props = Properties()
        if (Files.isRegularFile(path)) Files.newBufferedReader(path).use { props.load(it) }
        return props.entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
    }

    private fun writeMarkers(markers: Map<String, String>) {
        val props = Properties()
        markers.forEach { (k, v) -> props.setProperty(k, v) }
        val path = markerPath()
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { props.store(it, "Astera arena builds (ADR-0246): stage = version,digest,bounds") }
    }

    /** Empty chunks; the build is pasted afterwards. */
    private class VoidGenerator(private val x: Double, private val y: Double, private val z: Double) : ChunkGenerator() {
        override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, x, y, z)
    }

    private object PlainsOnly : BiomeProvider() {
        override fun getBiome(worldInfo: WorldInfo, x: Int, y: Int, z: Int): Biome = Biome.PLAINS
        override fun getBiomes(worldInfo: WorldInfo): List<Biome> = listOf(Biome.PLAINS)
    }

    private companion object {
        const val REPAIR_DELAY_TICKS: Long = 60L
        const val SAMPLES: Int = 8

        /** Builds above this many blocks are spread over ticks instead of placed in one go. */
        const val SYNC_BLOCK_LIMIT: Int = 50_000

        /** Wall-clock budget of the build pump per server tick (a tick is 50 ms). */
        const val TICK_BUDGET_NS: Long = 20_000_000L
    }
}
