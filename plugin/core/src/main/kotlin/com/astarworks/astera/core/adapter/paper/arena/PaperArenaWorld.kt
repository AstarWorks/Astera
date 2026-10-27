package com.astarworks.astera.core.adapter.paper.arena

import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.application.service.arena.ArenaImportContent
import com.astarworks.astera.core.application.service.arena.ArenaStructureContent
import com.astarworks.astera.core.application.service.instance.ArenaSlotBook
import com.astarworks.astera.core.application.service.instance.SlotAllocator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.*
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.nio.file.Path
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
    private data class Build(val stage: Stage, val plan: ArenaBuildPlan, val journal: ArenaPaster.Journal)
    private val book = ArenaSlotBook<Build>(slots, MAX_REBUILD_ATTEMPTS)
    private val manifest = SlotManifest(plugin.dataFolder.toPath().resolve("arena-slots.txt"))

    fun killY(worldName: String, x: Double, z: Double): Int? = book.entries().firstOrNull {
        it.build.stage.world.value == worldName && kotlin.math.abs(x - it.slot.x) < 1024 && kotlin.math.abs(z - it.slot.z) < 1024
    }?.build?.stage?.layout?.killY

    /** Authored structure parts, read once (off the region threads: first use is inside an Io task). */
    private val parts: Map<String, ArenaStructurePart> by lazy { ArenaStructureContent.load(contentDir) }

    /** Pre-built worlds of `content/arenas/` (ADR-0281), read once with the structure parts. */
    private val imports: Map<String, com.astarworks.astera.core.domain.stage.ArenaImport> by lazy { ArenaImportContent.load(contentDir) }

    /** The sky of the stage whose slot contains this point (MAP-08), or `null` outside every built slot. */
    fun atmosphereAt(worldName: String, x: Double, z: Double): ArenaAtmosphere? = book.entries().firstOrNull {
        it.build.stage.world.value == worldName && kotlin.math.abs(x - it.slot.x) < 1024 && kotlin.math.abs(z - it.slot.z) < 1024
    }?.build?.stage?.layout?.atmosphere

    fun installAll(): Pair<Int, Int> {
        val templates = stages()?.all()?.filter { it.layout != null }.orEmpty()
        val world = plugin.server.getWorld("world")
        val stale = manifest.load()
        if (world == null || stale.isEmpty()) { templates.forEach(::replenish); return templates.size to templates.size }
        // A previous run (restart, /reload, crash) left arenas in the slots; the allocator forgot them. Sweep them out
        // BEFORE the first paste: otherwise a shrunk layout keeps ghost islands and a rollback "restores" the old arena.
        log.info("Sweeping {} arena slot(s) left by an earlier run", stale.size)
        java.util.concurrent.CompletableFuture.allOf(*stale.values.map { paster.clear(world, it) }.toTypedArray()).whenComplete { _, _ ->
            scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                stale.keys.forEach(manifest::remove)
                templates.forEach(::replenish)
            }
        }
        return templates.size to templates.size
    }

    override fun resolve(stageId: StageId): Stage? = book.build(stageId)?.build?.stage
    override fun ensureBuilt(stageId: StageId): Boolean = book.contains(stageId)

    override fun peekReady(stage: Stage): Stage? =
        if (stage.layout == null) stage else book.ready(stage.id)?.build?.stage

    override fun acquire(stage: Stage): Result<Stage, ArenaAllocationError> {
        if (!Bukkit.isGlobalTickThread()) return Result.failure(ArenaAllocationError.WrongScope)
        if (stage.layout == null) return Result.failure(ArenaAllocationError.NotReady)
        val ready = book.ready(stage.id)
            ?: run { replenish(stage); return Result.failure(ArenaAllocationError.NotReady) }
        book.claim(ready)
        replenish(stage)
        return Result.success(ready.build.stage)
    }

    private fun replenish(template: Stage) {
        val world = plugin.server.getWorld("world") ?: return
        repeat(((template.warmSlots ?: warm) - book.waiting(template.id)).coerceAtLeast(0)) {
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
                        book.abandon(slot)
                        return@run
                    }
                    if (plan.minX < slot.x - 512 || plan.maxX > slot.x + 512 || plan.minZ < slot.z - 512 || plan.maxZ > slot.z + 512) {
                        log.error("Stage '{}' exceeds its slot footprint", template.id)
                        book.abandon(slot)
                        return@run
                    }
                    log.info("Arena '{}' plan: {} blocks in {} ms (off thread)", template.id, plan.blocks.size,
                        (System.nanoTime() - started) / 1_000_000)
                    val journal = ArenaPaster.Journal()
                    manifest.add(slot.index, ArenaPaster.Box(plan.minX, plan.maxX, plan.minY, plan.maxY, plan.minZ, plan.maxZ))
                    paster.paste(world, plan, journal).thenAccept { result ->
                        scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                            when (result) {
                                is Result.Success -> {
                                    book.publish(stage.id, ArenaSlotBook.Entry(slot, Build(stage, plan, result.value)), template.id)
                                    log.info("Arena '{}' ready in slot {}: {} blocks", template.id, slot.index, plan.blocks.size)
                                }
                                is Result.Failure -> {
                                    // A partial paste is quarantined (never reused dirty), rolled back, and only then freed:
                                    // otherwise the slot leaked for the whole uptime and the warm pool never refilled.
                                    log.error("Arena '{}' paste failed in slot {}: {}", template.id, slot.index, result.error)
                                    book.quarantine(slot)
                                    quarantine(world, template, slot, journal, 0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Rolls a failed paste back (retrying the rollback), frees the slot and tries the warm copy again (bounded). */
    private fun quarantine(world: org.bukkit.World, template: Stage, slot: SlotAllocator.Slot, journal: ArenaPaster.Journal, attempt: Int) {
        paster.rollback(world, journal).thenAccept { rolled ->
            scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                if (rolled is Result.Failure) {
                    if (attempt < MAX_REBUILD_ATTEMPTS) {
                        scheduler.run(ExecutionScope.Global, com.astarworks.astera.core.application.port.outbound.ticks(100)) {
                            quarantine(world, template, slot, journal, attempt + 1)
                        }
                    } else log.error("Arena '{}' slot {} could not be rolled back ({}); it stays quarantined", template.id, slot.index, rolled.error)
                    return@run
                }
                manifest.remove(slot.index)
                book.release(slot)
                if (book.buildFailed(template.id)) {
                    scheduler.run(ExecutionScope.Global, com.astarworks.astera.core.application.port.outbound.ticks(100)) { replenish(template) }
                }
            }
        }
    }

    override fun repairLater(stageId: StageId) {
        scheduler.run(ExecutionScope.Global, com.astarworks.astera.core.application.port.outbound.ticks(60)) {
            // Claimed once: a duplicate / stale request, or a pristine warm copy, is ignored (see ArenaSlotBook).
            val entry = book.beginRepair(stageId) ?: return@run
            val build = entry.build
            val template = stages()?.find(entry.slot.stage)
            val world = plugin.server.getWorld(build.stage.world.value)
            if (world == null) {
                // The world is gone (unloaded): nothing can be rolled back now; the manifest keeps the slot for the next
                // boot's sweep. Drop the stale build and free the slot instead of leaving it Resetting forever.
                log.warn("Arena slot {}: world '{}' is not loaded; dropping the stale build", entry.slot.index, build.stage.world.value)
                book.finishRepair(stageId, entry)
                template?.let(::replenish)
                return@run
            }
            // Roll back the paste journal (everything the match changed, blocks outside the island included), then rebuild.
            // The journal only knows what players broke/placed/exploded. Tool skills, fluids, fire, falling blocks, TNT
            // chains and mode objectives change blocks without those events, and those ghosts would sit in the slot
            // when it is handed to the next arena. So after the rollback the whole build box (plus margin) is swept to
            // void, entities included, before the slot is freed.
            val startedNanos = System.nanoTime()
            paster.rollback(world, build.journal).thenCompose { rolled ->
                val swept: java.util.concurrent.CompletableFuture<Result<Any?, ArenaPaster.Error>> =
                    if (rolled is Result.Success) paster.clear(world, sweepBox(world, build.plan, entry.slot)).thenApply<Result<Any?, ArenaPaster.Error>> { it }
                    else java.util.concurrent.CompletableFuture.completedFuture(rolled)
                swept
            }.thenAccept { result ->
                scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                    log.info("Arena slot {} repair ({}): {} ms wall, {} journaled blocks", entry.slot.index, if (result is Result.Success) "ok" else "failed", (System.nanoTime() - startedNanos) / 1_000_000, build.journal.before.size)
                    if (result is Result.Success) {
                        manifest.remove(entry.slot.index)
                        book.finishRepair(stageId, entry)
                        template?.let(::replenish)
                    } else {
                        // Keep the slot unavailable until the fallback paste has fully completed.
                        paster.paste(world, build.plan).thenAccept { pasted ->
                            scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                                if (pasted is Result.Success) {
                                    book.repasted(stageId, entry, build.copy(journal = pasted.value))
                                } else {
                                    log.error("Slot {} reset failed: {}", entry.slot.index, pasted)
                                    if (book.repairFailed(stageId)) repairLater(stageId)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The built box widened for strays (placed blocks beside the island, high-flying falling blocks), kept inside the slot. */
    private fun sweepBox(world: org.bukkit.World, plan: ArenaBuildPlan, slot: SlotAllocator.Slot): ArenaPaster.Box {
        fun clampX(v: Int) = v.coerceIn(slot.x - SWEEP_LIMIT, slot.x + SWEEP_LIMIT)
        fun clampZ(v: Int) = v.coerceIn(slot.z - SWEEP_LIMIT, slot.z + SWEEP_LIMIT)
        return ArenaPaster.Box(
            clampX(plan.minX - SWEEP_MARGIN), clampX(plan.maxX + SWEEP_MARGIN),
            (plan.minY - SWEEP_MARGIN).coerceAtLeast(world.minHeight), (plan.maxY + 2 * SWEEP_MARGIN).coerceAtMost(world.maxHeight - 1),
            clampZ(plan.minZ - SWEEP_MARGIN), clampZ(plan.maxZ + SWEEP_MARGIN),
        )
    }

    fun remember(block: org.bukkit.block.Block, previous: org.bukkit.block.data.BlockData = block.blockData) {
        val build = book.entries().firstOrNull {
            block.world.name == it.build.stage.world.value &&
                kotlin.math.abs(block.x - it.slot.x) < 768 && kotlin.math.abs(block.z - it.slot.z) < 768
        }?.build ?: return
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

    private companion object {
        const val MAX_REBUILD_ATTEMPTS = 5
        /** Blocks swept beyond the built box on each side. */
        const val SWEEP_MARGIN = 24
        /** Slots sit 2048 apart and plans stay within +-512, so a sweep never reaches a neighbour. */
        const val SWEEP_LIMIT = 1000
    }
}
