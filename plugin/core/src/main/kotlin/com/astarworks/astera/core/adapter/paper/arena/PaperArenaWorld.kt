package com.astarworks.astera.core.adapter.paper.arena

import com.astarworks.astera.core.application.service.arena.ArenaStructureContent
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
import org.bukkit.WeatherType
import org.bukkit.WorldCreator
import com.astarworks.astera.core.domain.stage.ArenaAtmosphere
import com.astarworks.astera.core.domain.stage.ArenaWeather
import java.util.UUID
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
 * Asynchronously plans authored arenas, then loads chunks and places/repairs blocks in bounded tick slices.
 * Match admission checks [ensureBuilt]; no caller can force a synchronous drain of a pending build.
 * Marker files are committed only after placement finishes. Runtime logs include measured maximum slice duration.
 */
internal class PaperArenaWorld(
    private val plugin: JavaPlugin,
    private val stages: () -> IStageRegistry?,
    private val contentDir: Path,
) : IArenaBuilder {

    private val log = LoggerFactory.getLogger(javaClass)
    private val server get() = plugin.server
    private val plans = HashMap<StageId, Pair<Stage, ArenaBuildPlan>>()
    private val repairs = HashSet<StageId>()

    private data class Prepared(val stage: Stage, val plan: ArenaBuildPlan, val digest: Int, val samples: List<Pair<ArenaBlockPos, String>>)
    private val ready = HashMap<StageId, Stage>()
    private val preparing = LinkedHashMap<StageId, Stage>()
    private var planning = false
    private val jobs = LinkedHashMap<StageId, Job>()
    private var pump: BukkitTask? = null

    private var atmosphereTask: BukkitTask? = null
    private val shownAtmosphere = HashMap<UUID, ArenaAtmosphere>()

    /**
     * Per-stage sky (MAP-08). Several stages share one arena world, so the world clock stays on the baseline and each
     * player inside a stage's footprint gets that stage's fixed player time and player weather; leaving resets both.
     */
    private fun tickAtmosphere() {
        val staged = plans.values.mapNotNull { (stage, plan) ->
            stage.layout?.atmosphere?.let { Triple(stage.world.value, plan, it) }
        }
        val online = server.onlinePlayers
        for (player in online) {
            val loc = player.location
            val worldName = loc.world?.name
            val hit = staged.firstOrNull { (world, plan, _) ->
                world == worldName && loc.blockX in plan.minX - ATMOSPHERE_MARGIN..plan.maxX + ATMOSPHERE_MARGIN &&
                    loc.blockZ in plan.minZ - ATMOSPHERE_MARGIN..plan.maxZ + ATMOSPHERE_MARGIN
            }?.third
            val shown = shownAtmosphere[player.uniqueId]
            if (hit == null) {
                if (shown != null) {
                    player.resetPlayerTime()
                    player.resetPlayerWeather()
                    shownAtmosphere.remove(player.uniqueId)
                }
            } else if (hit != shown) {
                player.setPlayerTime(hit.timeOfDay, false)
                player.setPlayerWeather(if (hit.weather == ArenaWeather.RAIN) WeatherType.DOWNFALL else WeatherType.CLEAR)
                shownAtmosphere[player.uniqueId] = hit
            }
        }
        shownAtmosphere.keys.retainAll(online.map { it.uniqueId }.toSet())
    }

    fun killY(worldName: String): Int? =
        stages()?.all()?.mapNotNull { stage -> stage.layout?.takeIf { stage.world.value == worldName }?.killY }?.minOrNull()

    /** Accept builds at boot. Ready means both planning and all placement slices have finished. */
    fun installAll(): Pair<Int, Int> {
        val withLayout = stages()?.all()?.filter { it.layout != null }.orEmpty()
        withLayout.forEach { request(it) }
        atmosphereTask?.cancel()
        atmosphereTask = server.scheduler.runTaskTimer(plugin, Runnable { tickAtmosphere() }, 20L, ATMOSPHERE_PERIOD_TICKS)
        return withLayout.size to withLayout.size
    }

    override fun ensureBuilt(stageId: StageId): Boolean {
        val stage = stages()?.find(stageId) ?: return true
        if (stage.layout == null) return true
        if (!server.isPrimaryThread) {
            server.scheduler.runTask(plugin, Runnable { request(stage) })
            return false
        }
        if (ready[stageId] == stage && stageId !in jobs) return true
        request(stage)
        return false
    }

    private fun request(stage: Stage) {
        if (ready[stage.id] == stage || stage.id in jobs || stage.id in preparing) return
        preparing[stage.id] = stage
        prepareNext()
    }

    /** One planner at a time bounds temporary memory. Pure generation, parsing and digesting never run on a tick. */
    private fun prepareNext() {
        if (planning) return
        val stage = preparing.values.firstOrNull() ?: return
        planning = true
        server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val start = System.nanoTime()
            val result = runCatching {
                val plan = ArenaIslandGenerator.generate(stage, ArenaStructureContent.load(contentDir))
                require(plan.problems.isEmpty()) { plan.problems.joinToString() }
                Prepared(stage, plan, plan.digest(), sample(plan))
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            server.scheduler.runTask(plugin, Runnable {
                preparing.remove(stage.id)
                planning = false
                result.onSuccess { prepared ->
                    plans[stage.id] = stage to prepared.plan
                    log.info("Arena '{}' plan: {} blocks in {} ms (off thread)", stage.id.value, prepared.plan.blocks.size, ms)
                    runCatching { enqueue(prepared) }.onFailure { log.error("Arena '{}' enqueue failed", stage.id.value, it) }
                }.onFailure { log.error("Arena '{}' planning failed", stage.id.value, it) }
                prepareNext()
            })
        })
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

    private fun enqueue(prepared: Prepared) {
        val (stage, plan, digest, samples) = prepared
        val layout = requireNotNull(stage.layout)
        val world = worldOf(stage) ?: return
        val tickets = linkedSetOf<Pair<Int, Int>>()
        val expected = Marker(layout.markerVersion, digest, plan.minX, plan.maxX, plan.minY, plan.maxY, plan.minZ, plan.maxZ)
        val steps = sequence {
            // Load sample chunks asynchronously too: even a marker check must not load chunks on a tick.
            for ((pos, _) in samples) yieldAll(loadChunk(world, pos.x, pos.z, tickets))
            val previous = readMarkers()[stage.id.value]?.let(Marker::parse)
            fun intact() = samples.all { (pos, state) ->
                world.getBlockAt(pos.x, pos.y, pos.z).type == Material.matchMaterial(state.substringBefore('['))
            }
            if (previous != expected || !intact()) {
                val clearBox = when {
                    previous != null -> Box(previous.minX, previous.maxX, previous.minY, previous.maxY, previous.minZ, previous.maxZ)
                    samples.any { (pos, _) -> !world.getBlockAt(pos.x, pos.y, pos.z).type.isAir } ->
                        Box(plan.minX, plan.maxX, plan.minY, plan.maxY, plan.minZ, plan.maxZ)
                    else -> null
                }
                if (clearBox != null) yieldAll(clearSteps(world, clearBox, tickets))
                yieldAll(pasteSteps(world, plan, false, null, tickets))
            }
            yieldAll(releaseSteps(world, tickets))
        }.iterator()
        jobs[stage.id] = Job(stage.id, steps, cleanup = { release(world, tickets) }) { elapsedMs, ticks, maxSliceNs, operations ->
            val latest = readMarkers()
            latest[stage.id.value] = expected.toString()
            writeMarkers(latest)
            ready[stage.id] = stage
            log.info("Arena '{}' ready: {} blocks, {} ms, {} ticks, max slice {} us, {} operations",
                stage.id.value, plan.blocks.size, elapsedMs, ticks, maxSliceNs / 1_000, operations)
        }
        startPump()
    }

    private fun repair(stageId: StageId) {
        val stage = stages()?.find(stageId) ?: return
        if (stage.layout == null || stageId in jobs) return
        val world = server.getWorld(stage.world.value) ?: return
        val (cached, plan) = plans[stageId] ?: return
        if (cached != stage) { request(stage); return }
        val fixed = intArrayOf(0)
        val tickets = linkedSetOf<Pair<Int, Int>>()
        val steps = sequence {
            yieldAll(pasteSteps(world, plan, true, fixed, tickets))
            yieldAll(releaseSteps(world, tickets))
        }
        jobs[stageId] = Job(stageId, steps.iterator(),
            cleanup = { release(world, tickets) }) { ms, ticks, maxNs, operations ->
            log.info("Arena '{}' repaired: {} blocks in {} ms, {} ticks, max slice {} us, {} operations",
                stageId.value, fixed[0], ms, ticks, maxNs / 1_000, operations)
        }
        startPump()
    }

    // ── tick pump ─────────────────────────────────────────────────────────

    /** One build: [steps] does the work a slice at a time, [onDone] runs once everything was placed. */
    private class Job(
        val stageId: StageId,
        private val steps: Iterator<Unit>,
        val cleanup: () -> Unit,
        private val onDone: (Long, Int, Long, Long) -> Unit,
    ) {
        private val startNs = System.nanoTime()
        private var ticks = 0
        private var operations = 0L
        private var maxSliceNs = 0L

        fun run(deadlineNs: Long): Boolean {
            val start = System.nanoTime()
            ticks++
            var n = 0
            while (n < MAX_OPERATIONS_PER_TICK && System.nanoTime() < deadlineNs && steps.hasNext()) {
                steps.next()
                n++
            }
            operations += n
            // Do not advance the coroutine again outside the deadline just to test hasNext().
            val done = n < MAX_OPERATIONS_PER_TICK && System.nanoTime() < deadlineNs && !steps.hasNext()
            maxSliceNs = maxOf(maxSliceNs, System.nanoTime() - start)
            return done
        }

        fun complete() {
            cleanup()
            onDone((System.nanoTime() - startNs) / 1_000_000, ticks, maxSliceNs, operations)
        }
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
                job.cleanup()
                ready.remove(job.stageId)
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
    private fun pasteSteps(world: World, plan: ArenaBuildPlan, onlyMissing: Boolean, counter: IntArray?, tickets: MutableSet<Pair<Int, Int>>): Sequence<Unit> = sequence {
        val cache = HashMap<String, BlockData>()
        fun data(state: String): BlockData = cache.getOrPut(state) { server.createBlockData(state) }
        for ((pos, state) in plan.blocks) {
            yield(Unit)
            if (onlyMissing && pos in plan.volatileCells) continue
            yieldAll(loadChunk(world, pos.x, pos.z, tickets))
            val wanted = data(state)
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (onlyMissing && block.blockData == wanted) continue
            block.setBlockData(wanted, false)
            if (counter != null) counter[0]++
        }
    }

    private data class Box(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val minZ: Int, val maxZ: Int)

    /** Wipes [box] (plus a small margin) one inspected block per step: the previous build may have been larger or elsewhere. */
    private fun clearSteps(world: World, box: Box, tickets: MutableSet<Pair<Int, Int>>): Sequence<Unit> = sequence {
        val air = server.createBlockData(Material.AIR)
        val minY = box.minY - 2
        val maxY = box.maxY + 12
        for (x in box.minX - 4..box.maxX + 4) {
            for (z in box.minZ - 4..box.maxZ + 4) {
                yieldAll(loadChunk(world, x, z, tickets))
                for (y in minY..maxY) {
                    val block = world.getBlockAt(x, y, z)
                    if (!block.type.isAir) block.setBlockData(air, false)
                    yield(Unit)
                }
            }
        }
    }

    /** Never synchronously acquire a chunk from a placement/clear/marker operation. Tickets prevent unload during a job. */
    private fun loadChunk(world: World, x: Int, z: Int, tickets: MutableSet<Pair<Int, Int>>): Sequence<Unit> = sequence {
        val chunk = (x shr 4) to (z shr 4)
        if (chunk in tickets) return@sequence
        val future = world.getChunkAtAsync(chunk.first, chunk.second, true)
        while (!future.isDone) yield(Unit)
        future.join()
        world.addPluginChunkTicket(chunk.first, chunk.second, plugin)
        tickets += chunk
        yield(Unit)
    }

    private fun releaseSteps(world: World, tickets: MutableSet<Pair<Int, Int>>): Sequence<Unit> = sequence {
        val it = tickets.iterator()
        while (it.hasNext()) {
            val (x, z) = it.next()
            world.removePluginChunkTicket(x, z, plugin)
            it.remove()
            yield(Unit)
        }
    }

    private fun release(world: World, tickets: Set<Pair<Int, Int>>) {
        tickets.forEach { (x, z) -> world.removePluginChunkTicket(x, z, plugin) }
    }

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
        const val ATMOSPHERE_PERIOD_TICKS: Long = 10L
        const val ATMOSPHERE_MARGIN: Int = 48

        /** Wall-clock budget of the build pump per server tick (a tick is 50 ms). */
        const val TICK_BUDGET_NS: Long = 4_000_000L
        const val MAX_OPERATIONS_PER_TICK: Int = 1024
    }
}
