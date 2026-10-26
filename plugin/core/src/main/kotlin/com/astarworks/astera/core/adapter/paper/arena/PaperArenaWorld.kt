package com.astarworks.astera.core.adapter.paper.arena

import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.application.service.arena.ArenaImportContent
import com.astarworks.astera.core.application.service.arena.ArenaStructureContent
import com.astarworks.astera.core.application.service.instance.SlotAllocator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.*
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** All slot mutations run on Global. A match can claim only a completed paste. */
internal class PaperArenaWorld(
    private val plugin: JavaPlugin,
    private val stages: () -> IStageRegistry?,
    private val scheduler: IScheduler,
    private val slots: SlotAllocator,
    private val contentDir: Path,
) : IArenaBuilder {
    private val log = LoggerFactory.getLogger(javaClass)
    private fun setting(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
    private val warm = setting("ASTERA_WARM_SLOTS", 1).also { require(it in 1..16) }
    private val paster = ArenaPaster(plugin, scheduler, setting("ASTERA_PASTE_BLOCKS_PER_TICK", 4096).also { require(it > 0) })
    private data class Build(val slot: SlotAllocator.Slot, val stage: Stage, val plan: ArenaBuildPlan,
                             val journal: ArenaPaster.Journal)
    private val builds = ConcurrentHashMap<StageId, Build>()

    fun killY(worldName: String, x: Double, z: Double): Int? = builds.values.firstOrNull {
        it.stage.world.value == worldName && kotlin.math.abs(x - it.slot.x) < 1024 && kotlin.math.abs(z - it.slot.z) < 1024
    }?.stage?.layout?.killY

    /** Authored structure parts, read once (off the region threads: first use is inside an Io task). */
    private val parts: Map<String, ArenaStructurePart> by lazy { ArenaStructureContent.load(contentDir) }

    /** Pre-built worlds of `content/arenas/` (ADR-0281), read once with the structure parts. */
    private val imports: Map<String, com.astarworks.astera.core.domain.stage.ArenaImport> by lazy { ArenaImportContent.load(contentDir) }

    /** The sky of the stage whose slot contains this point (MAP-08), or `null` outside every built slot. */
    fun atmosphereAt(worldName: String, x: Double, z: Double): ArenaAtmosphere? = builds.values.firstOrNull {
        it.stage.world.value == worldName && kotlin.math.abs(x - it.slot.x) < 1024 && kotlin.math.abs(z - it.slot.z) < 1024
    }?.stage?.layout?.atmosphere

    fun installAll(): Pair<Int, Int> {
        val templates = stages()?.all()?.filter { it.layout != null }.orEmpty()
        templates.forEach(::replenish)
        return templates.size to templates.size
    }

    override fun resolve(stageId: StageId): Stage? = builds[stageId]?.stage
    override fun ensureBuilt(stageId: StageId): Boolean = builds.containsKey(stageId)

    /** The completed build of a Ready slot of [stage]'s template; the single source for both [peekReady] and [acquire]. */
    private fun readyBuild(stage: Stage): Pair<SlotAllocator.Slot, Build>? {
        val ready = slots.snapshot().firstOrNull { it.stage == stage.id && it.state == SlotAllocator.State.Ready } ?: return null
        val built = builds.values.firstOrNull { it.slot.index == ready.index } ?: return null
        return ready to built
    }

    override fun peekReady(stage: Stage): Stage? =
        if (stage.layout == null) stage else readyBuild(stage)?.second?.stage

    override fun acquire(stage: Stage): Result<Stage, ArenaAllocationError> {
        if (!Bukkit.isGlobalTickThread()) return Result.failure(ArenaAllocationError.WrongScope)
        if (stage.layout == null) return Result.failure(ArenaAllocationError.NotReady)
        val (ready, built) = readyBuild(stage)
            ?: run { replenish(stage); return Result.failure(ArenaAllocationError.NotReady) }
        slots.state(ready.index, SlotAllocator.State.Running)
        replenish(stage)
        return Result.success(built.stage)
    }

    private fun replenish(template: Stage) {
        val world = plugin.server.getWorld("world") ?: return
        val waiting = slots.snapshot().count { it.stage == template.id && it.state in setOf(SlotAllocator.State.Ready, SlotAllocator.State.Building) }
        repeat(((template.warmSlots ?: warm) - waiting).coerceAtLeast(0)) {
            val slot = slots.allocate(template.id) ?: return
            val stage = placed(template, slot)
            // Planning is pure CPU (no world access): off the tick threads, then back on Global to commit.
            scheduler.run(ExecutionScope.Io, Duration.ZERO) {
                val started = System.nanoTime()
                val plan = runCatching { ArenaIslandGenerator.generate(stage, parts, imports) }
                    .onFailure { log.error("Arena '{}' planning failed", template.id, it) }
                    .getOrNull()
                scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                    if (plan == null || plan.problems.isNotEmpty()) {
                        plan?.let { log.error("Arena '{}' has layout problems: {}", template.id, it.problems) }
                        slots.free(slot.index)
                        return@run
                    }
                    if (plan.minX < slot.x - 512 || plan.maxX > slot.x + 512 || plan.minZ < slot.z - 512 || plan.maxZ > slot.z + 512) {
                        log.error("Stage '{}' exceeds its slot footprint", template.id)
                        slots.free(slot.index)
                        return@run
                    }
                    log.info("Arena '{}' plan: {} blocks in {} ms (off thread)", template.id, plan.blocks.size,
                        (System.nanoTime() - started) / 1_000_000)
                    paster.paste(world, plan).thenAccept { result ->
                        scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                            when (result) {
                                is Result.Success -> {
                                    builds[stage.id] = Build(slot, stage, plan, result.value)
                                    slots.state(slot.index, SlotAllocator.State.Ready)
                                    log.info("Arena '{}' ready in slot {}: {} blocks", template.id, slot.index, plan.blocks.size)
                                }
                                is Result.Failure -> {
                                    // A partial paste remains quarantined; never reuse dirty terrain.
                                    log.error("Arena '{}' paste failed in slot {}: {}", template.id, slot.index, result.error)
                                    slots.state(slot.index, SlotAllocator.State.Resetting)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun repairLater(stageId: StageId) {
        scheduler.run(ExecutionScope.Global, com.astarworks.astera.core.application.port.outbound.ticks(60)) {
            val build = builds[stageId] ?: return@run
            slots.state(build.slot.index, SlotAllocator.State.Resetting)
            val world = plugin.server.getWorld(build.stage.world.value) ?: return@run
            // Match journal recovery runs first. Roll back the paste journal, then rebuild the warm copy.
            paster.rollback(world, build.journal).thenAccept { result ->
                scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                    if (result is Result.Success) {
                        builds.remove(stageId)
                        slots.free(build.slot.index)
                        stages()?.find(build.slot.stage)?.let(::replenish)
                    } else {
                        // Keep the slot unavailable until the fallback paste has fully completed.
                        paster.paste(world, build.plan).thenAccept { pasted ->
                            scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                                if (pasted is Result.Success) {
                                    builds[stageId] = build.copy(journal = pasted.value)
                                    slots.state(build.slot.index, SlotAllocator.State.Ready)
                                } else log.error("Slot {} reset failed: {}", build.slot.index, pasted)
                            }
                        }
                    }
                }
            }
        }
    }

    fun remember(block: org.bukkit.block.Block, previous: org.bukkit.block.data.BlockData = block.blockData) {
        val build = builds.values.firstOrNull {
            block.world.name == it.stage.world.value &&
                kotlin.math.abs(block.x - it.slot.x) < 768 && kotlin.math.abs(block.z - it.slot.z) < 768
        } ?: return
        build.journal.before.putIfAbsent(ArenaBlockPos(block.x, block.y, block.z), previous.clone())
    }

    private fun placed(template: Stage, slot: SlotAllocator.Slot): Stage {
        fun Vec3.shift(): Vec3 = copy(x = x + slot.x, z = z + slot.z)
        val world = WorldId("world")
        return template.copy(
            id = StageId("${template.id.value}-slot-${slot.index}"), world = world,
            origin = Vec3(slot.x.toDouble(), 0.0, slot.z.toDouble()),
            spawnPoints = template.spawnPoints.map { it.copy(world = world, position = it.position.shift()) },
            regions = template.regions.map { it.copy(min = it.min.shift(), max = it.max.shift()) },
            pillars = template.pillars.map { it.copy(position = it.position.shift()) },
            layout = template.layout?.let { layout -> layout.copy(
                islands = layout.islands.map { it.copy(x = it.x + slot.x, z = it.z + slot.z) },
                landmarks = layout.landmarks.map { it.copy(x = it.x + slot.x, z = it.z + slot.z) },
                structures = layout.structures.map { it.copy(x = it.x + slot.x, z = it.z + slot.z) },
                imported = layout.imported?.let { it.copy(x = it.x + slot.x, z = it.z + slot.z) },
            ) },
        )
    }
}
