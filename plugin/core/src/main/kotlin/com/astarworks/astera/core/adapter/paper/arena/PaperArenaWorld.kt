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
 *  - [repairLater] re-places blocks players destroyed during a match, leaving objective marker cells alone.
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

    /** Y below which a player is killed in [worldName], or `null` when no layout stage lives there. */
    fun killY(worldName: String): Int? =
        stages()?.all()?.mapNotNull { stage -> stage.layout?.takeIf { stage.world.value == worldName }?.killY }?.minOrNull()

    /** Builds every layout stage. Returns `(built or already built, stages with a layout)`. */
    fun installAll(): Pair<Int, Int> {
        val withLayout = stages()?.all()?.filter { it.layout != null }.orEmpty()
        return withLayout.count { build(it) } to withLayout.size
    }

    override fun ensureBuilt(stageId: StageId): Boolean {
        val stage = stages()?.find(stageId) ?: return true
        if (stage.layout == null) return true
        if (!server.isPrimaryThread) {
            server.scheduler.runTask(plugin, Runnable { build(stage) })
            return true
        }
        return build(stage)
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

    private fun build(stage: Stage): Boolean {
        val layout = stage.layout ?: return true
        return try {
            val world = worldOf(stage) ?: return false
            val plan = planFor(stage)
            val markers = readMarkers()
            val expected = Marker(layout.markerVersion, plan.digest(), plan.minX, plan.maxX, plan.minY, plan.maxY, plan.minZ, plan.maxZ)
            val previous = markers[stage.id.value]?.let(Marker::parse)
            if (previous == expected && sampleIntact(world, plan)) {
                log.info("Arena '{}' already built (marker {})", stage.id.value, layout.markerVersion)
                return true
            }
            if (previous != null || !sampleEmpty(world, plan)) clearBounds(world, plan, previous)
            paste(world, plan, onlyMissing = false)
            markers[stage.id.value] = expected.toString()
            writeMarkers(markers)
            log.info("Arena '{}' built: {} blocks, {} islands", stage.id.value, plan.blocks.size, layout.islands.size)
            true
        } catch (t: Throwable) {
            log.error("Arena '{}' could not be built: {}", stage.id.value, t.message, t)
            false
        }
    }

    private fun repair(stageId: StageId) {
        val stage = stages()?.find(stageId) ?: return
        if (stage.layout == null) return
        val world = server.getWorld(stage.world.value) ?: return
        val fixed = paste(world, planFor(stage), onlyMissing = true)
        if (fixed > 0) log.info("Arena '{}' repaired: {} block(s) re-placed", stageId.value, fixed)
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

    /** Places the plan; with [onlyMissing] only blocks that differ from the plan and are not objective cells. */
    private fun paste(world: World, plan: ArenaBuildPlan, onlyMissing: Boolean): Int {
        val cache = HashMap<String, BlockData?>()
        fun data(state: String): BlockData? = cache.getOrPut(state) {
            runCatching { server.createBlockData(state) }
                .onFailure { log.warn("Arena build: invalid block data '{}': {}", state, it.message) }
                .getOrNull()
        }
        var placed = 0
        for ((pos, state) in plan.blocks) {
            if (onlyMissing && pos in plan.volatileCells) continue
            val wanted = data(state) ?: continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (onlyMissing && block.blockData == wanted) continue
            block.setBlockData(wanted, false)
            placed++
        }
        return placed
    }

    /** Wipes the previous and the new build's bounding boxes (the layout may have moved or shrunk). */
    private fun clearBounds(world: World, plan: ArenaBuildPlan, previous: Marker?) {
        val minX = minOf(plan.minX, previous?.minX ?: plan.minX) - 4
        val maxX = maxOf(plan.maxX, previous?.maxX ?: plan.maxX) + 4
        val minY = minOf(plan.minY, previous?.minY ?: plan.minY) - 2
        val maxY = maxOf(plan.maxY, previous?.maxY ?: plan.maxY) + 12
        val minZ = minOf(plan.minZ, previous?.minZ ?: plan.minZ) - 4
        val maxZ = maxOf(plan.maxZ, previous?.maxZ ?: plan.maxZ) + 4
        val air = server.createBlockData(Material.AIR)
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                for (y in minY..maxY) {
                    val block = world.getBlockAt(x, y, z)
                    if (!block.type.isAir) block.setBlockData(air, false)
                }
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
    }
}
