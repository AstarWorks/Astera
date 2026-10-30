package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.domain.skillengine.table.drainsAfterExit
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.runtime.CastError
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.ops.DashDriver
import com.astarworks.astera.core.application.skillengine.runtime.ops.ReelDriver
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.world.WorldRows
import com.astarworks.astera.core.application.skillengine.runtime.port.LinePath
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.application.skillengine.load.grammar.LocalAxes
import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.hit.DefaultContactResolver
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
import com.astarworks.astera.core.domain.skillengine.model.AnchorDecl
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.AnchorMode
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.Callee
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EntityRef
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.Guard
import com.astarworks.astera.core.domain.skillengine.table.LockMove
import com.astarworks.astera.core.domain.skillengine.table.HitShape
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.animation.skinrig.BodySkeleton
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigPlayback
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.application.skillengine.load.MeshModels
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.Num
import com.astarworks.astera.core.domain.skillengine.table.Rot
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.application.skillengine.compose.AlignCell
import com.astarworks.astera.core.application.skillengine.compose.ProjectileKinematics
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.LockEnd
import com.astarworks.astera.core.domain.skillengine.table.HitWindow
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.Vec
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.PullMath
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Something the player could not do and said so (no silent drops). The cast keeps playing; game-sim asserts the list is empty. */
public data class PlayerFault(val cast: CastHandle, val tick: Int, val src: String, val message: String)

/**
 * What the player cannot own itself: CEL guard evaluation (W1-24) and caster input locks (movement / look gateways).
 * Defaults say "cannot": the player records a [PlayerFault] and treats a guard as false.
 */
public interface PlayerHooks {
    /** True / false for the guard at [localTick] ticks into the owning state; null = cannot evaluate. */
    public fun guard(cast: CastHandle, guard: Guard, conditions: Map<String, String>, binding: EventBinding?, localTick: Int, seed: Long = 0L): Boolean? = null

    /** Starts holding the caster's inputs until server tick [untilTick] (absolute); false = unsupported. */
    public fun lock(caster: UUID, move: LockMove, pitch: Boolean, yaw: Boolean, untilTick: Int): Boolean = false

    public fun unlock(caster: UUID, move: LockMove, pitch: Boolean, yaw: Boolean) {}

    /**
     * One `set` row (item 15c) for a field a display handle carries and the visual gateway has no method for (`text_opacity`).
     * false = unsupported. `count` and `volume` never reach here: the player folds them into the instance's later particle / sound rows.
     */
    public fun setDisplayField(handle: VisualHandle, field: String, value: Double): Boolean = false

    /** One `set` row whose value is text: `color` (`#rrggbb`) and `text` (a sprite flipbook glyph). false = unsupported. */
    public fun setDisplayText(handle: VisualHandle, field: String, value: String): Boolean = false
}

private object NoHooks : PlayerHooks

/** A bounce whose rebound is slower than this (blocks per tick) rolls instead of counting. */
private const val REST_SPEED = 0.05

/** The spawn kinds that are one visible display and follow a moving parent ([TablePlayer.followRiders]); ribbons and lines have their own drivers. */
private val RIDER_KINDS = setOf(SpawnKind.ITEM, SpawnKind.BLOCK, SpawnKind.TEXT, SpawnKind.SPRITE)

/** A bounced body restarts this far off the surface along its normal, so the next tick's ray does not begin inside the block. */
private const val CONTACT_SKIN = 0.02

/**
 * Plays the expansion table (ADR-0303 C7). It chooses no schedule: it looks up rows, pastes sub-tables at event ticks and
 * hands gateways the payloads, with `Val.Live` cells filled from [LiveValueSource]. Tick order is 10.2a:
 * 1 state rows, 2 projectile bodies (block events), 3 events in 10.2 order with `@0` rows and same-tick cascades, 4 (inside 3),
 * 5 cleanup.
 */
public class TablePlayer(
    private val visual: ISkillVisualGateway,
    private val particles: ISkillParticleGateway,
    private val sounds: ISkillSoundGateway,
    private val gameplay: ISkillGameplayGateway,
    private val query: ISkillEntityQuery,
    private val live: LiveValueSource,
    private val trace: CastTrace = CastTrace.NONE,
    private val movement: ISkillMovementGateway,
    private val items: ISkillItemGateway,
    private val world: ISkillWorldGateway,
) : ISkillTablePlayer {
    /** Server caps (`maxLifeTicks`, `maxCastTicks`); set by the platform from `EngineConfig`. */
    public var config: EngineConfig = EngineConfig()

    public var hooks: PlayerHooks = NoHooks

    private val runs = LinkedHashMap<Long, Run>()
    private val faultLog = ArrayList<PlayerFault>()
    private var nextCast = 1L
    private var nextSynthetic = -1L
    private var lastTick = 0
    private val resolver = DefaultContactResolver()

    public fun faults(): List<PlayerFault> = faultLog.toList()

    public fun clearFaults() {
        faultLog.clear()
    }

    // ------------------------------------------------------------------ API

    override fun start(request: CastRequest, skill: CompiledSkill): Result<CastHandle, CastError> {
        // a `frames_in` name is bound and a `required` param is valued by a caller's `use:`; a skill cast directly has nobody to do it
        if (skill.def.isCalleeOnly) {
            return Result.Failure(CastError.CalleeOnly(skill.def.id, skill.def.framesIn.keys.toList(), skill.def.unvaluedRequiredParams))
        }
        // the table's first state is the entry (MachineTables puts it there): a part call as `machine.start` is expanded to its child path
        val states = skill.table.states.associateBy { it.state }
        val rootPath = skill.table.states.firstOrNull()?.state ?: StatePath(skill.def.machine.start)
        if (rootPath !in states) return Result.Failure(CastError.FrameSetup("start state '${rootPath.value}' has no table in ${skill.table.skillId}"))
        val handle = CastHandle(nextCast++)
        val root = LevelCursor(rootPath, request.startTick, 0)
        val cursor = CastCursor(handle, skill, SeededStream(request.seed), listOf(root), LinkedHashMap(), ArrayList())
        val listened = HashSet<EventPath>(skill.table.subTables.keys)
        skill.table.states.forEach { st -> st.branches.forEach { listened += it.event } }
        skill.table.subTables.values.forEach { sub -> sub.branches.forEach { listened += it.event } }
        runs[handle.value] = Run(cursor, request, states, listened, DashDriver(CastRef(handle.value, request.caster, request.world, request.skill.def.item, request.heldItem), movement, query))
        lastTick = max(lastTick, request.startTick)
        return Result.Success(handle)
    }

    override fun tick(now: Int) {
        lastTick = now
        world.tick()
        for (run in runs.values.toList()) {
            if (run.finalized || now < run.request.startTick) continue
            // Rows fault inside playRow; anything else thrown here (drivers, sweeps, transitions) must end THIS cast only. An uncaught throw
            // would abort the whole tick loop on every later tick, so a single poisoned cast silently froze every cast behind it.
            try {
                tickRun(run, now)
            } catch (e: RuntimeException) {
                fault(run, now, "tick", "cast aborted, ${e.javaClass.simpleName}: ${e.message}")
                try {
                    finalize(run, now)
                } catch (cleanup: RuntimeException) {
                    fault(run, now, "tick", "cleanup of the aborted cast threw ${cleanup.javaClass.simpleName}: ${cleanup.message}")
                } finally {
                    run.finalized = true
                }
            }
        }
        runs.values.removeAll { it.finalized }
    }

    override fun deliver(cast: CastHandle, event: RuntimeEvent) {
        runs[cast.value]?.external?.add(event)
    }

    override fun absorbs(cast: CastHandle, event: RuntimeEvent, now: Int): Boolean {
        val run = runs[cast.value]?.takeIf { !it.finalized } ?: return false
        return absorbsRun(run, event, now)
    }

    private fun absorbsRun(run: Run, event: RuntimeEvent, now: Int): Boolean {
        val binding = EventBinding(event.path, event.index, event.contact, event.victim, event.attacker)
        for (lvl in run.cursor.levels.sortedByDescending { it.depth }) {
            if (lvl.done) continue
            val st = run.states[lvl.state] ?: continue
            val lt = now - lvl.enteredAt
            val br = st.branches.firstOrNull { b -> b.event == event.path && lt in b.window.min..b.window.max && guardOk(run, b, binding, lt, now) } ?: continue
            return br.absorb
        }
        return false
    }

    /**
     * A hit of [run]'s caster on [victim] that the victim's own casts absorb (`absorb: true` on their `hurt` transition): the hit is
     * cancelled for the attacker (no damage, no `hit`, nothing counted) and the victim's casts receive the `hurt` event, so the absorbing
     * transition fires. The platform's damage-pipeline interceptor never sees such a hit (it never reaches the pipeline). Called once per
     * victim and contact. Founder 2026-10-29: absorbed = miss for the attacker, who gets `<op>.absorbed` instead.
     */
    private fun absorbedBy(run: Run, victim: UUID, now: Int): Boolean {
        val theirs = runs.values.filter { it !== run && !it.finalized && it.request.caster == victim }
        val hurt = RuntimeEvent(EventPath("hurt"), now, null, null, null, run.request.caster)
        if (theirs.none { absorbsRun(it, hurt, now) }) return false
        theirs.forEach { it.external.add(hurt) }
        return true
    }

    override fun cancel(cast: CastHandle, scope: CancelScope) {
        val run = runs[cast.value] ?: return
        if (run.finalized) return
        val now = lastTick
        // a cast already ended and only lingering for its detached visuals: cancel cuts them
        if (run.finishing) {
            run.cutting = true
            // rule D: the gameplay rows still held by a drain (and any detached visuals) are dropped with the cast, never silently
            for (sc in run.cursor.subCursors) {
                for (row in sc.sub.rows.drop(sc.nextRow)) if (row.drainsAfterExit || row.detached) trace.cut(cast, row.src.value, now, sc.owner ?: run.cursor.skill.table.states.first().state, ExitReason.CANCEL)
            }
            finalize(run, now)
            runs.values.removeAll { it.finalized }
            return
        }
        cancelScope(run, scope, now, null)
        settle(run, now)
        runs.values.removeAll { it.finalized }
    }

    override fun live(): List<CastHandle> = runs.values.filter { !it.finalized && !it.finishing }.map { it.cursor.handle }

    override fun running(): List<CastHandle> = runs.values.filter { !it.finalized }.map { it.cursor.handle }

    // ------------------------------------------------------------------ one tick (10.2a)

    private fun tickRun(run: Run, now: Int) {
        if (now - run.request.startTick >= config.maxCastTicks) {
            fault(run, now, "cast", "cast older than max_cast_ticks (${config.maxCastTicks}); swept without exit actions")
            finalize(run, now)
            return
        }
        // a run in its tail is not a live cast: it takes no input
        if (run.finishing) run.external.clear()
        run.pending += run.external.toList().also { run.external.clear() }
        run.inTick = true
        // 0. R45: the machine-root `world` rows ride the cast clock, owned by no state
        advanceWorld(run, now)
        // 0. caster / frame drivers (dash steps, reel steps, rig poses) of rows that started on earlier ticks
        driversStep(run, now)
        // 1. state rows (every level), then the later rows of sub-tables pasted on earlier ticks
        for (lvl in run.cursor.levels.toList()) if (lvl in run.cursor.levels) advanceLevel(run, lvl, now)
        for (sc in run.cursor.subCursors.toList()) advanceSub(run, sc, now)
        run.cursor.subCursors.removeAll { it.nextRow >= it.sub.rows.size }
        // 2. projectile bodies: swept block contact
        projectileStep(run, now)
        // 3 + 4. events in 10.2 order, timers, same-tick cascade
        runWaves(run, now, withTimers = true)
        // 4b. whatever rides a projectile body that moved this tick (flight, bounce / reflect rebase, a key, a rig) follows it
        followRiders(run, now)
        // 5. observers see every live frame at its settled pose, then cleanup
        traceFrames(run, now)
        // cleanup
        run.inTick = false
        cleanup(run, now)
    }

    /**
     * A display that rides a projectile body (`anchor: <body>`) is written only by its own ops, so a body that moves by itself (its
     * flight, a bounce / reflect rebase, a body mounted on its victim) left it behind at its spawn pose. Once per tick, after every move of the tick: when the
     * parent's world pose is not the one this child last saw, the child is written at its own world pose (1 tick of interpolation, as the parent moves per tick). The
     * parent's whole chain is read, so a grandchild follows too. Same path in the game-sim and on Paper.
     */
    private fun followRiders(run: Run, now: Int) {
        for (rec in run.recs.values.toList()) {
            val h = rec.real ?: continue
            val parent = rec.anchor?.let { run.recs[it] } ?: continue
            if (rec.kind !in RIDER_KINDS || rec.rig != null || parent.kind != SpawnKind.PROJECTILE_BODY) continue
            val pw = worldOf(run, parent, parent.localAt(now), null, now)
            val seen = rec.parentSeen
            rec.parentSeen = pw
            if (seen != null && samePose(seen, pw)) continue
            visual.write(h, visualPose(rec, worldOf(run, rec, rec.localAt(now), null, now)), 1)
        }
    }

    private fun samePose(a: PoseV, b: PoseV): Boolean =
        a.pos.distanceSquared(b.pos) < 1e-12 && abs(a.rot.x - b.rot.x) + abs(a.rot.y - b.rot.y) + abs(a.rot.z - b.rot.z) + abs(a.rot.w - b.rot.w) < 1e-9

    /** [CastTrace.frame] for every live authored `frame` and every projectile body (not a body clip) at its world pose this tick. */
    private fun traceFrames(run: Run, now: Int) {
        for ((id, rec) in run.recs) {
            if ((rec.kind != SpawnKind.FRAME && rec.kind != SpawnKind.PROJECTILE_BODY) || rec.body != null) continue
            val w = worldOf(run, rec, rec.localAt(now), null, now)
            trace.frame(run.cursor.handle, id, now, Vec3(w.pos.x, w.pos.y, w.pos.z), Quaternion(w.rot.x, w.rot.y, w.rot.z, w.rot.w))
        }
    }

    private fun advanceWorld(run: Run, now: Int) {
        val rows = run.cursor.skill.table.worldRows
        val ctx = RowCtx(run.request.startTick, null, null, null)
        while (run.nextWorld < rows.size && rows[run.nextWorld].tick <= now - ctx.base) playRow(run, rows[run.nextWorld++], ctx, now)
    }

    /** Used by [cancel] outside a tick: events produced by the exit rows, then cleanup. */
    private fun settle(run: Run, now: Int) {
        runWaves(run, now, withTimers = false)
        cleanup(run, now)
    }

    private fun runWaves(run: Run, now: Int, withTimers: Boolean) {
        val cap = run.cursor.skill.table.bounds.sameTickDepth + 1
        var depth = 1
        var first = true
        while (run.pending.isNotEmpty() || (first && withTimers)) {
            if (depth > cap) {
                fault(run, now, "cascade", "same-tick event cascade deeper than $cap (table sameTickDepth ${cap - 1}); dropped ${run.pending.size} events")
                run.pending.clear()
                return
            }
            val wave = run.pending.sortedBy { if (it.path.value.startsWith("input.")) 0 else 1 }
            run.pending.clear()
            for (ev in wave) deliverEvent(run, ev, now)
            if (first && withTimers) timers(run, now)
            first = false
            depth++
        }
    }

    private fun timers(run: Run, now: Int) {
        // a state entered by a timer this tick whose own timer is due at once (a final-bound `after: 0`) fires in the same tick, bounded by the same-tick depth
        val cap = run.cursor.skill.table.bounds.sameTickDepth + 1
        var round = 0
        do {
            var fired = false
            for (lvl in run.cursor.levels.toList()) {
                if (lvl !in run.cursor.levels || lvl.done) continue
                val lt = now - lvl.enteredAt
                if (lvl.firedTick == now && lt != 0) continue
                val st = run.states[lvl.state] ?: continue
                // `after:` and `for:` + `then:` are both `timer(n)` branches; declaration order, first match wins (item 14.8)
                val br = st.branches.firstOrNull { b ->
                    b.event.value.startsWith("timer(") && lt in b.window.min..b.window.max &&
                        guardOk(run, b, EventBinding(b.event, null, null, null), lt, now)
                } ?: continue
                transition(run, lvl, br.target, br.subTable, ExitReason.TIMER, now, EventBinding(br.event, null, null, null))
                fired = true
            }
        } while (fired && ++round < cap)
    }

    private fun cleanup(run: Run, now: Int) {
        for (id in run.pendingEnds.toList()) endInstance(run, id)
        run.pendingEnds.clear()
        for (rec in run.recs.entries.toList()) {
            if (now - rec.value.spawnTick >= config.maxLifeTicks) {
                fault(run, now, rec.key.text, "instance older than max_life_ticks (${config.maxLifeTicks}); swept")
                endInstance(run, rec.key)
            }
        }
        for (l in run.locks.toList()) {
            if (now >= l.untilAbs) {
                run.locks.remove(l)
                hooks.unlock(run.request.caster, l.move, l.pitch, l.yaw)
            }
        }
        if (run.finishing) {
            // rev4.14 rule D: the cast has ended (locks, drivers and state-owned instances go now), but the gameplay rows its handlers still hold are the cast's
            // and play at their load ticks; the tail (or the end of the run) starts when the last of them has played. A cancel drops them with everything else.
            if (!run.released) {
                run.released = true
                releaseCast(run)
                if (!run.cutting) {
                    val pending = run.cursor.subCursors.map { sc -> sc to sc.sub.rows.drop(sc.nextRow).filter { it.drainsAfterExit } }.filter { it.second.isNotEmpty() }
                    if (pending.isNotEmpty()) {
                        trace.drain(run.cursor.handle, now, pending.sumOf { it.second.size }, pending.maxOf { (sc, rows) -> sc.firedAt + rows.maxOf { it.tick } })
                    }
                }
            }
            if (!run.cutting && run.cursor.subCursors.any { sc -> sc.sub.rows.drop(sc.nextRow).any { it.drainsAfterExit } }) return
            val tail = run.cursor.skill.table.bounds.tail
            if (tail <= 0 || run.cutting) {
                finalize(run, now)
            } else {
                if (run.tailEnd == Int.MIN_VALUE) {
                    beginTail(run)
                    run.tailEnd = now + tail
                }
                if (now >= run.tailEnd) finalize(run, now)
            }
        }
    }

    /**
     * The cast ended this tick (locks, drivers and every instance the cast's states own end, exactly as without a tail); what stays is the
     * `detach: true` visuals, which play on until [Run.tailEnd] (rev4.12). The run is no longer a live cast.
     */
    private fun beginTail(run: Run) {
        releaseCast(run)
        val keep = run.cursor.subCursors.mapNotNull { sc ->
            val rest = sc.sub.rows.drop(sc.nextRow).filter { it.detached }
            if (rest.isEmpty()) null else sc.withRows(rest)
        }
        run.cursor.subCursors.clear()
        run.cursor.subCursors += keep
    }

    /** The cast's own things end with the cast: the instances of its states, the drivers of non-detached rows, the locks; nothing more is accepted from outside. */
    private fun releaseCast(run: Run) {
        for (id in run.recs.keys.toList().asReversed()) if (run.recs[id]?.detached == false) endInstance(run, id)
        stopDrivers(run, keepDetached = true)
        for (l in run.locks.toList()) hooks.unlock(run.request.caster, l.move, l.pitch, l.yaw)
        run.locks.clear()
        run.external.clear()
    }

    private fun finalize(run: Run, now: Int) {
        if (run.finalized) return
        for (id in run.recs.keys.toList().asReversed()) endInstance(run, id)
        stopDrivers(run, keepDetached = false)
        for (l in run.locks.toList()) hooks.unlock(run.request.caster, l.move, l.pitch, l.yaw)
        run.locks.clear()
        run.cursor.subCursors.clear()
        run.finalized = true
    }

    // ------------------------------------------------------------------ levels, transitions

    private fun advanceLevel(run: Run, lvl: LevelCursor, now: Int) {
        val st = run.states[lvl.state] ?: return
        val lt = now - lvl.enteredAt
        while (lvl.nextRow < st.rows.size && st.rows[lvl.nextRow].tick <= lt) {
            val row = st.rows[lvl.nextRow++]
            playRow(run, row, RowCtx(lvl.enteredAt, lvl.entryBinding, lvl.state, lvl), now)
        }
    }

    private fun advanceSub(run: Run, sc: SubCursor, now: Int) {
        while (sc.nextRow < sc.sub.rows.size && sc.sub.rows[sc.nextRow].tick + sc.firedAt <= now) {
            val row = sc.sub.rows[sc.nextRow++]
            playRow(run, row, RowCtx(sc.firedAt, sc.binding, sc.owner, null, chain = sc.chain), now)
        }
    }

    private fun enterLevel(run: Run, state: StatePath, parent: LevelCursor?, now: Int, at: Int? = null, binding: EventBinding? = null): LevelCursor? {
        val st = run.states[state]
        if (st == null) {
            fault(run, now, state.value, "state has no table")
            return null
        }
        val lvl = LevelCursor(state, now, 0)
        lvl.parent = parent
        lvl.entryBinding = binding
        lvl.firedTick = now
        val levels = run.cursor.levels.toMutableList()
        levels.add(min(at ?: levels.size, levels.size), lvl)
        run.cursor.levels = levels
        advanceLevel(run, lvl, now)
        if (st.isFinal) {
            lvl.done = true
            levelFinal(run, lvl, now)
        }
        return lvl
    }

    private fun levelFinal(run: Run, lvl: LevelCursor, now: Int) {
        // a final child announces `<parent>.end` itself (an `Emit` row of its table, counted per region in [emitRow])
        if (lvl.parent != null) return
        run.finishing = true
        run.pending += RuntimeEvent(EventPath("end"), now, null, null, null)
    }

    /**
     * `until: <instance>.end` naming an instance of another state (or of the cast's world) keeps the row alive past its own state's exit:
     * the table already holds its End row in that instance's `.end` sub-table, which closes it when that instance ends.
     */
    private fun endsInOtherState(run: Run, id: InstanceId, state: StatePath): Boolean =
        run.cursor.skill.table.subTables.any { (path, sub) ->
            if (!path.value.endsWith(".end")) return@any false
            val owner = path.value.removeSuffix(".end").substringBeforeLast('/', "")
            owner != state.value && !owner.startsWith(state.value + "/") &&
                sub.rows.any { (it.action as? Action.End)?.instance == id }
        }

    private fun exitLevel(run: Run, lvl: LevelCursor, reason: ExitReason, now: Int, binding: EventBinding?) {
        for (child in run.cursor.levels.filter { it.parent === lvl }.asReversed()) exitLevel(run, child, ExitReason.PREEMPT, now, binding)
        cutOwnedVisuals(run, lvl, reason, now)
        pasteSub(run, EventPath("${lvl.state.value}.exit[${reason.name.lowercase()}]"), now, binding, owner = null, direct = true)
        handOffDetached(run, lvl, now)
        // End rows the table already played this tick are the contract and run in row order; the sweep below only closes what is still alive
        for (id in run.pendingEnds.toList()) endInstance(run, id)
        run.pendingEnds.clear()
        for ((id, rec) in run.recs.entries.toList().asReversed()) if (rec.owner == lvl.state && !rec.detached && !endsInOtherState(run, id, lvl.state)) endInstance(run, id)
        // a reel is a driver, not an instance: it belongs to the state that started it and stops when that state leaves (timer before arrival)
        for (id in run.reels.keys.toList()) if (id.text.startsWith(lvl.state.value + "/")) endDrivers(run, id)
        run.regionEnds.remove(lvl.state)
        for (l in run.locks.filter { it.owner == lvl.state }) {
            run.locks.remove(l)
            hooks.unlock(run.request.caster, l.move, l.pitch, l.yaw)
        }
        run.cursor.levels = run.cursor.levels.filter { it !== lvl }
    }

    /**
     * rev4.14 rule D3: the presentation rows a handler of [lvl] still holds die with the state, and never silently: each is removed from its cursor
     * (so it cannot spawn into a dead state) and traced as `cut`. Gameplay rows ([drainsAfterExit]) and `detach: true` rows stay.
     */
    private fun cutOwnedVisuals(run: Run, lvl: LevelCursor, reason: ExitReason, now: Int) {
        for (sc in run.cursor.subCursors.toList()) {
            if (sc.owner != lvl.state) continue
            val rest = sc.sub.rows.drop(sc.nextRow)
            val cut = rest.filter { !it.detached && !it.drainsAfterExit && it.action !is Action.End }
            if (cut.isEmpty()) continue
            for (row in cut) trace.cut(run.cursor.handle, row.src.value, now, lvl.state, reason)
            val kept = sc.withRows(rest.filter { it !in cut })
            // the new cursor replaces the old in place, keeping the rows' ticks relative to the firing
            val at = run.cursor.subCursors.indexOf(sc)
            run.cursor.subCursors.removeAt(at)
            if (kept.sub.rows.isNotEmpty()) run.cursor.subCursors.add(at, kept)
        }
    }

    /**
     * `detach: true` (rev4.12): the rows of [lvl]'s table that are still to come and belong to the cast keep playing after the state left, on the
     * state's own clock (base = its entry tick). Everything else of the table is dropped with the state, as before.
     */
    private fun handOffDetached(run: Run, lvl: LevelCursor, now: Int) {
        if (run.cutting) return
        val st = run.states[lvl.state] ?: return
        val rest = st.rows.drop(lvl.nextRow).filter { it.detached }
        if (rest.isEmpty()) return
        val key = EventPath("${lvl.state.value}.detached")
        val sc = SubCursor(SubTable(key, lvl.state, TickRange(0, 0), 1, rest, emptyList()), lvl.enteredAt, 0, lvl.entryBinding ?: EventBinding(key, null, null, null))
        sc.owner = lvl.state
        lvl.nextRow = st.rows.size
        run.cursor.subCursors += sc
        advanceSub(run, sc, now)
        if (sc.nextRow >= sc.sub.rows.size) run.cursor.subCursors.remove(sc)
    }

    /** exit of the source, the transition's `do`, entry of the target (10.1). A null [target] is an internal handler. */
    private fun transition(run: Run, lvl: LevelCursor, target: StatePath?, doSub: EventPath?, reason: ExitReason, now: Int, binding: EventBinding?) {
        if (target == null) {
            if (doSub != null) pasteSub(run, doSub, now, binding, owner = lvl.state, direct = true)
            return
        }
        lvl.firedTick = now
        val at = run.cursor.levels.indexOf(lvl)
        val parent = lvl.parent
        exitLevel(run, lvl, reason, now, binding)
        if (doSub != null) pasteSub(run, doSub, now, binding, owner = null, direct = true)
        enterLevel(run, target, parent, now, at, binding)
    }

    // ------------------------------------------------------------------ events

    private fun deliverEvent(run: Run, ev: RuntimeEvent, now: Int) {
        if (!bodyEventLive(run, ev.path)) return
        run.seen += ev.path
        val binding = EventBinding(ev.path, ev.index, ev.contact, ev.victim, ev.attacker)
        val offered = offer(run, ev, binding, now, only = null)
        val sub = run.cursor.skill.table.subTables[ev.path] ?: return
        if (ev.path in offered.pasted) return
        // a machine-root handler (world op) is cast-relative and survives every state exit; any other is bounded by its owner state's life
        val root = sub.owner == SubTable.MACHINE_ROOT
        // a state-owned handler whose state has already exited is late by design (the event outlived its owner); it is dropped, and said so in the trace
        val ownerLevel = if (root) null else run.cursor.levels.firstOrNull { it.state == sub.owner }
        if (!root && ownerLevel == null) { trace.dropped(run.cursor.handle, ev.path, now, "owner-exited:${sub.owner.value}"); return }
        val lt = now - (ownerLevel?.enteredAt ?: run.request.startTick)
        if (lt < sub.window.min || lt > sub.window.max) { trace.dropped(run.cursor.handle, ev.path, now, "outside-window:lt=$lt"); return }
        if ((run.firings[ev.path] ?: 0) >= sub.maxFirings) return
        run.firings[ev.path] = (run.firings[ev.path] ?: 0) + 1
        pasteSub(run, ev.path, now, binding, owner = if (root) null else sub.owner, direct = false)
    }

    /**
     * R16: a body event that ends the flight (`hit` with `once`, `block` in despawn / stick mode) marks the body ended; an `expire` of an
     * ended body is dropped, so `expire` fires on range / `for` only and never after a hit or a block despawn.
     */
    private fun bodyEventLive(run: Run, path: EventPath): Boolean {
        val dot = path.value.lastIndexOf('.')
        if (dot < 0) return true
        val key = path.value.substring(dot + 1)
        if (key != "hit" && key != "block" && key != "expire") return true
        val rec = run.recs.entries.firstOrNull { it.value.kind == SpawnKind.PROJECTILE_BODY && it.key.text == path.value.substring(0, dot) }?.value ?: return true
        if (key == "expire") return !rec.ended
        if (key in rec.endsOn) rec.ended = true
        return true
    }

    private class Offered(val handled: Boolean, val pasted: Set<EventPath>)

    /**
     * Offers [ev] to the levels innermost first (item 14.8). Per level the branches are tried in declaration order and the first
     * match wins; a matched transition on a level that already transitioned this tick does not handle the event, so it bubbles.
     * A handled event is consumed: the handler's ancestors are not offered it.
     */
    private fun offer(run: Run, ev: RuntimeEvent, binding: EventBinding, now: Int, only: LevelCursor?): Offered {
        val pasted = HashSet<EventPath>()
        val consumed: MutableSet<LevelCursor> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        var any = false
        val order = (only?.let { listOf(it) } ?: run.cursor.levels.sortedByDescending { it.depth })
        for (lvl in order) {
            if (lvl.done || lvl in consumed || lvl !in run.cursor.levels) continue
            val st = run.states[lvl.state] ?: continue
            val lt = now - lvl.enteredAt
            val br = st.branches.firstOrNull { b ->
                b.event == ev.path && lt in b.window.min..b.window.max && guardOk(run, b, binding, lt, now)
            } ?: continue
            if (br.target != null && lvl.firedTick == now) continue
            any = true
            consumed += lvl
            generateSequence(lvl.parent) { it.parent }.forEach { consumed += it }
            br.subTable?.let { pasted += it }
            if (br.absorb) gameplay.cancelIncomingHit(castRef(run), ev.attacker)
            transition(run, lvl, br.target, br.subTable, if (ev.path.value == "cancel") ExitReason.CANCEL else ExitReason.EVENT, now, binding)
        }
        return Offered(any, pasted)
    }

    private fun guardOk(run: Run, b: Branch, binding: EventBinding, lt: Int, now: Int): Boolean {
        val g = b.guard ?: return true
        val r = hooks.guard(run.cursor.handle, g, run.cursor.skill.table.conditions, binding, lt, run.request.seed)
        if (r == null) fault(run, now, b.event.value, "no guard evaluator for $g")
        return r == true
    }

    private fun pasteSub(run: Run, key: EventPath, now: Int, binding: EventBinding?, owner: StatePath?, direct: Boolean) {
        val sub = run.cursor.skill.table.subTables[key] ?: return
        if (direct && sub.rows.isEmpty()) return
        val sc = SubCursor(sub, now, 0, binding ?: EventBinding(key, null, null, null))
        sc.owner = owner
        run.cursor.subCursors += sc
        advanceSub(run, sc, now)
        if (sc.nextRow >= sub.rows.size) run.cursor.subCursors.remove(sc)
    }

    /** `<parent>.end` of a parallel parent is published once every region is final (item 7); any other event goes out as is. */
    private fun emitRow(run: Run, path: EventPath, now: Int, binding: EventBinding?) {
        if (path.value.endsWith(".end")) {
            val parent = StatePath(path.value.removeSuffix(".end"))
            val st = run.states[parent]
            if (st != null && run.cursor.levels.any { it.state == parent }) {
                val prefix = parent.value + "/"
                val regions = st.rows.count { r ->
                    val a = r.action
                    a is Action.Enter && a.state.value.startsWith(prefix) && !a.state.value.substring(prefix.length).contains('/')
                }
                if (regions > 1) {
                    val n = (run.regionEnds[parent] ?: 0) + 1
                    run.regionEnds[parent] = n
                    if (n < regions) return
                }
            }
        }
        emit(run, path, now, binding)
    }

    private fun emit(run: Run, path: EventPath, now: Int, binding: EventBinding?) {
        run.pending += RuntimeEvent(path, now, binding?.index, binding?.contact, binding?.victim, binding?.attacker)
    }

    // ------------------------------------------------------------------ cancel

    private fun cancelScope(run: Run, scope: CancelScope, now: Int, ctx: RowCtx?) {
        when (scope) {
            CancelScope.Cast -> {
                run.cutting = true
                val binding = ctx?.binding
                // event `cancel` to the innermost level (an `on: cancel` handler), CANCEL reason up the chain, then End(root)
                val innermost = run.cursor.levels.maxByOrNull { it.depth }
                if (innermost != null) {
                    val ev = RuntimeEvent(EventPath("cancel"), now, null, null, null)
                    offer(run, ev, EventBinding(ev.path, null, null, null), now, only = innermost)
                }
                // ADR-0303 3.6 / 10.6: `end` fires also on cancel, so every body still in flight announces `<body>.end` while its handlers are in scope
                for ((id, rec) in run.recs.entries.toList().asReversed()) {
                    if (rec.kind != SpawnKind.PROJECTILE_BODY || rec.ended) continue
                    rec.ended = true
                    deliverEvent(run, RuntimeEvent(EventPath(id.text + ".end"), now, id.path.lastOrNull()?.index, null, null), now)
                }
                for (lvl in run.cursor.levels.sortedByDescending { it.depth }) {
                    if (lvl !in run.cursor.levels) continue
                    exitLevel(run, lvl, ExitReason.CANCEL, now, binding)
                }
                run.pending += RuntimeEvent(EventPath("end"), now, null, null, null)
                run.finishing = true
                stopDrivers(run)
                world.revertCast(castRef(run))
            }
            CancelScope.State -> {
                // the innermost level exits with CANCEL into its `then`
                val lvl = ctx?.level ?: run.cursor.levels.firstOrNull { it.state == ctx?.owner } ?: return
                val then = run.states[lvl.state]?.then
                if (then == null) {
                    fault(run, now, lvl.state.value, "cancel scope state: the state has no 'then'")
                    return
                }
                if (lvl in run.cursor.levels && lvl.firedTick != now) transition(run, lvl, then, null, ExitReason.CANCEL, now, ctx?.binding)
            }
            is CancelScope.Instance -> {
                if (scope.id !in run.recs && !hasDriver(run, scope.id)) return
                endInstance(run, scope.id)
                emit(run, EventPath(scope.id.text + ".end"), now, ctx?.binding)
            }
        }
    }

    // ------------------------------------------------------------------ instances

    private fun endInstance(run: Run, id: InstanceId) {
        val prefix = id.path
        endDrivers(run, id)
        // a state's (or the cast root's) sweep leaves the instances it does not own: detached ones end by their own End row or at the run's end
        val sweeperDetached = run.recs[id]?.detached ?: false
        val doomed = run.recs.entries.filter { (k, r) ->
            k.path.size >= prefix.size && k.path.subList(0, prefix.size) == prefix && (k == id || sweeperDetached || !r.detached)
        }.map { it.key }
        for (k in doomed.asReversed()) {
            val rec = run.recs[k] ?: continue
            // ADR-0303 9 (`frames_out`, l.166/170/808): a finished body stays readable to its caller as a frozen frame at its last pose
            if (rec.kind == SpawnKind.FRAME || rec.kind == SpawnKind.PROJECTILE_BODY) {
                freshBody(rec)
                // a finished `body` keeps riding the caster with its clip's last named points; any other frame freezes where it ended
                val ridesCaster = rec.body != null
                val w = worldOf(run, rec, rec.localAt(lastTick), null, lastTick)
                val frozen = if (ridesCaster) PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0)) else PoseV(Vector3d(w.pos), Quaterniond(w.rot), Vector3d(w.scale))
                run.finished[k] = Rec(rec.kind, null, if (ridesCaster) "caster" else "world", lastTick, rec.owner, rec.detached, frozen).also { it.points.putAll(rec.points) }
            }
            run.recs.remove(k)
            run.lines.remove(k)
            run.cursor.instances.remove(k)
            run.bodyClips.remove(k)?.let { (who, clip) -> gameplay.stopBodyClip(castRef(run), who, clip) }
            if (run.heldModels.remove(k)) gameplay.clearHeldModel(castRef(run), run.request.caster)
            run.veils.remove(k)?.handles?.forEach { visual.remove(it) }
            rec.real?.let { visual.remove(it) }
        }
    }

    private fun worldOf(run: Run, rec: Rec, local: PoseV, callerBinding: EventBinding?, t: Int, depth: Int = 0): PoseV {
        // a body mounted on its victim (`hit.attach`) always resolves `victim.*` through the hit that mounted it, whoever asks
        val binding = if (rec.attachTo != null && rec.binding?.victim != null) rec.binding else callerBinding ?: rec.binding
        var pp = Vector3d()
        var pr = Quaterniond()
        val parent = rec.anchor?.let { run.recs[it] ?: run.finished[it] }
        if (parent != null && depth < 16) {
            val pw = worldOf(run, parent, parent.localAt(t), binding, t, depth + 1)
            pp = pw.pos
            pr = pw.rot
        } else if (rec.anchorName.isNotEmpty() && rec.anchorName != "world") {
            val scope = RowScope.cast(run.request.seed)
            pp = namedVec(run, rec.anchorName, binding, scope)
            pr = namedRot(run, rec.anchorName, binding, scope)
        }
        if (parent == null) {
            when (rec.axes) {
                "world" -> pr = Quaterniond()
                "caster" -> pr = live.rot(run.cursor.handle, "caster", binding, RowScope.cast(run.request.seed))
                "victim" -> pr = live.rot(run.cursor.handle, "target", binding, RowScope.cast(run.request.seed))
            }
        }
        return PoseV(Vector3d(local.pos).rotate(pr).add(pp), Quaterniond(pr).mul(local.rot), Vector3d(local.scale))
    }

    private fun visualPose(rec: Rec, w: PoseV): VisualPose =
        VisualPose(
            Frame(Vec3(w.pos.x, w.pos.y, w.pos.z), Quaternion(w.rot.x, w.rot.y, w.rot.z, w.rot.w)),
            Vec3(w.scale.x, w.scale.y, w.scale.z),
            rec.billboard,
        )

    // ------------------------------------------------------------------ rows

    /** [scope] is set by [playRow] before the row's cells are read (the row's seed and copy index); the bare default is the cast's. */
    private class RowCtx(val base: Int, val binding: EventBinding?, val owner: StatePath?, val level: LevelCursor?, var scope: RowScope = RowScope.cast(0L), val chain: ChainState? = null) {
        /** The row being played is `detach: true`: what it spawns is owned by the cast ([playRow] sets it). */
        var detached: Boolean = false
    }

    /**
     * Chain targeting (ADR-0303 9.4): a row of hop k runs with the hop's pick bound as `victim` and the previous pick as `from`; once a hop
     * found nobody, its rows and every later hop's are skipped. Null = the row does not play.
     */
    private fun chainCtx(row: Row, ctx: RowCtx): RowCtx? {
        val rc = row.chain ?: return ctx
        val st = ctx.chain ?: return null
        if (rc.chain in st.dead) return null
        val picks = st.picks[rc.chain].orEmpty()
        val prev = ctx.binding?.victim
        val base = ctx.binding ?: EventBinding(EventPath(rc.chain), null, null, null)
        val from = if (rc.hop >= 2) picks.getOrNull(rc.hop - 2) else prev
        val victim = if (row.action is Action.Pick) prev else picks.getOrNull(rc.hop - 1) ?: return null
        return RowCtx(ctx.base, base.copy(victim = victim, from = from), ctx.owner, ctx.level, ctx.scope, st)
    }

    /** The pick row of hop k: the nearest enemy to `from` that the chain has not picked yet (and that is not the event's own victim). */
    private fun pickRow(run: Run, row: Row, a: Action.Pick, ctx: RowCtx, now: Int, src: String) {
        val st = ctx.chain
        val b = ctx.binding
        if (st == null || b?.from == null) {
            fault(run, now, src, "chain pick has no 'from' binding")
            return
        }
        val picks = st.picks.getOrPut(a.chain) { ArrayList() }
        // the row's victim is the event's own (chainCtx keeps it for the pick row); `from` is the previous hop's pick
        val excluded = HashSet<UUID>()
        picks.forEach { excluded += it.id }
        b.victim?.let { excluded += it.id }
        val chosen = areaTargets(run, a.area, ctx).firstOrNull { it !in excluded }
        trace.pick(run.cursor.handle, row.instance, now, chosen)
        if (chosen == null) st.dead += a.chain else picks += EntityRef(chosen)
    }

    private fun playRow(run: Run, row: Row, ctx0: RowCtx, now: Int) {
        // ADR-0303 2.0a: `until` is a mask over candidate rows; the named event cuts every remaining candidate
        if (row.maskUntil.isNotEmpty() && row.maskUntil.any { it in run.seen }) return
        val ctx = chainCtx(row, ctx0)
        if (ctx == null) {
            if (row.action is Action.Pick) trace.pick(run.cursor.handle, row.instance, now, null)
            return
        }
        val src = row.src.value.ifEmpty { row.instance.text }
        val g = row.guard
        if (g != null) {
            // a class-L / seeded `when` on a point gameplay op (ADR-0303 item 13): the row exists, the guard decides at its tick
            val ok = hooks.guard(run.cursor.handle, g, run.cursor.skill.table.conditions, ctx.binding, now - ctx.base, guardSeed(run, row, ctx, now))
            if (ok == null) { fault(run, now, src, "no guard evaluator for $g"); return }
            if (!ok) return
        }
        ctx.scope = rowScope(run, row, ctx.binding)
        ctx.detached = row.detached
        trace.row(run.cursor.handle, row.instance, now)
        try {
            when (val a = row.action) {
                is Action.Enter -> {
                    trace.enter(run.cursor.handle, a.state, now)
                    val lvl = ctx.level
                    if (lvl != null && a.state != lvl.state) {
                        if (a.state.value.startsWith(lvl.state.value + "/")) enterLevel(run, a.state, lvl, now, binding = ctx.binding ?: lvl.entryBinding)
                        else fault(run, now, src, "Enter(${a.state.value}) is not a child of ${lvl.state.value}")
                    }
                }
                is Action.Exit -> trace.exit(run.cursor.handle, a.state, a.reason, now)
                is Action.Pick -> pickRow(run, row, a, ctx, now, src)
                is Action.Spawn -> spawn(run, row, a, ctx, now)
                is Action.Key -> key(run, a, ctx, now)
                is Action.Teleport -> teleport(run, a, ctx, now)
                is Action.TrailPoint -> trailPoint(run, a, ctx, now)
                is Action.Particle -> particle(run, row, a, ctx)
                is Action.Sound -> sound(run, row, a, ctx)
                is Action.Hit -> hit(run, row, a, ctx, now)
                is Action.Damage -> damage(run, row, a, ctx, now)
                is Action.ParticleDef -> particleDef(run, row, a, ctx)
                is Action.Set -> set(run, row, a, ctx, now, src)
                is Action.Status -> for (v in ruleTargets(run, a.targets, ctx)) {
                    // a victim this cast's damage already killed gets no status (named in the trace, never silently dropped)
                    if (v in run.killed) {
                        trace.statusSkippedDead(run.cursor.handle, row.instance, now, v)
                        continue
                    }
                    gameplay.applyStatus(run.request.caster, v, a.status, int(run, a.stacks, ctx), a.forTicks)
                }
                is Action.Effect -> effect(run, row, a, ctx, now, src)
                is Action.World -> WorldRows.execute(world, castRef(run), a, vec(run, a.origin, ctx), vec(run, a.forward, ctx), eye = { runCatching { live.vec(run.cursor.handle, "caster.eye", ctx.binding, ctx.scope) }.getOrNull() ?: vec(run, a.origin, ctx) }) { cell -> num(run, cell as Num, ctx) }
                is Action.Lock -> {
                    val until = when (val end = a.until) {
                        is LockEnd.AtTick -> ctx.base + end.tick
                        // released by the owning level exiting or the cast ending; the bound is the server cap
                        LockEnd.CastEnd -> run.request.startTick + config.maxCastTicks
                    }
                    if (hooks.lock(run.request.caster, a.move, a.pitch, a.yaw, until)) {
                        run.locks += LockRec(ctx.owner, a.move, a.pitch, a.yaw, until)
                    } else {
                        fault(run, now, src, "no lock sink in PlayerHooks (move=${a.move} pitch=${a.pitch} yaw=${a.yaw})")
                    }
                }
                is Action.Call -> {
                    trace.call(run.cursor.handle, a.callId, now)
                    val c = a.callee
                    if (c is Callee.SkillRef && (c.gateCooldown || c.gateCost)) {
                        items.applyGate(castRef(run), c.id, c.gateCooldown, c.gateCost)
                    }
                }
                is Action.Emit -> {
                    trace.event(run.cursor.handle, a.event, now, ctx.binding?.index)
                    emitRow(run, a.event, now, ctx.binding)
                }
                is Action.End -> {
                    // R16: a body reaching its End row on its own (range / `for`) announces `expire` then `end`; a hit / cancel already ended it
                    val body = run.recs[a.instance]?.takeIf { it.kind == SpawnKind.PROJECTILE_BODY && !it.ended }
                    if (body != null) {
                        val idx = a.instance.path.lastOrNull()?.index
                        if (body.expireTick == now) {
                            run.pending += RuntimeEvent(EventPath(a.instance.text + ".expire"), now, idx, expireContact(run, body, now), null)
                            body.expireTick = -1
                        }
                        run.pending += RuntimeEvent(EventPath(a.instance.text + ".end"), now, idx, null, null)
                    }
                    // a frame the reel is pulling back outlives its scheduled End (the flight window): it goes with the cast, not mid-reel
                    val reeled = run.reels.values.any { it.frame == a.instance }
                    if (!reeled && a.instance !in run.pendingEnds) run.pendingEnds += a.instance
                }
                is Action.Cancel -> cancelScope(run, a.scope, now, ctx)
            }
        } catch (e: RuntimeException) {
            fault(run, now, src, "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun spawn(run: Run, row: Row, a: Action.Spawn, ctx: RowCtx, now: Int) {
        val id = row.instance
        run.recs[id]?.let { endInstance(run, id) }
        val local = PoseV(vec(run, a.pose.position, ctx), quat(run, a.pose.rotation, ctx, if (a.kind == SpawnKind.PROJECTILE_BODY) null else a.anchor, if (a.kind == SpawnKind.PROJECTILE_BODY) "" else a.anchorName), vec(run, a.pose.scale, ctx))
        // a body's spawn pose is already the origin anchor's WORLD pose (`anchor:<origin>.position`); anchoring it again would count the anchor twice
        val body = a.kind == SpawnKind.PROJECTILE_BODY
        val rec = Rec(a.kind, if (body) null else a.anchor, if (body) "" else a.anchorName, now, ctx.owner, ctx.detached, local)
        rec.binding = ctx.binding
        rec.billboard = a.look.fields["billboard"]?.let { runCatching { DisplayBillboard.valueOf(strOf(run, it, ctx).uppercase()) }.getOrNull() } ?: DisplayBillboard.FIXED
        rec.axes = a.look.fields["axes"]?.let { strOf(run, it, ctx) }
        run.finished.remove(id)
        run.recs[id] = rec
        for ((key, v) in a.look.fields) if (key.startsWith("point.")) rec.points[key.removePrefix("point.")] = any(run, v, ctx) as Vector3d
        if (body) {
            rec.flight = flightOf(run, a.look, local, ctx)
            val lf = a.look.fields
            val mode = lf["block.mode"]?.let { strOf(run, it, ctx) }
            val stuck = if (mode == "stick") lf["block.for"]?.let { dbl(run, it, ctx).toInt() } ?: 0 else 0
            lf["end.max"]?.let { rec.expireTick = now + dbl(run, it, ctx).toInt() - stuck }
            rec.hitTargets = lf["hit.targets"]?.let { strOf(run, it, ctx) } ?: ""
            rec.hitRadius = lf["radius"]?.let { dbl(run, it, ctx) } ?: 0.0
            lf["hit.shape.radius"]?.let { rec.shapeRadius = dbl(run, it, ctx) }
            rec.shapeFrames = generateSequence(0) { it + 1 }.takeWhile { lf.containsKey("hit.shape.frame.$it") }.map { i ->
                ShapeFrame(strOf(run, lf.getValue("hit.shape.frame.$i"), ctx), strOf(run, lf.getValue("hit.shape.frame.$i.from"), ctx), strOf(run, lf.getValue("hit.shape.frame.$i.to"), ctx))
            }.toList()
            rec.shapeSelf = lf["hit.shape.self.from"]?.let { f -> ShapeSelf(strOf(run, f, ctx), strOf(run, lf.getValue("hit.shape.self.to"), ctx)) }
            rec.blockDespawn = mode == "despawn"
            if (mode == "bounce") {
                rec.bounceMode = true
                rec.restitution = lf["block.restitution"]?.let { dbl(run, it, ctx) } ?: 0.0
                rec.friction = lf["block.friction"]?.let { dbl(run, it, ctx) } ?: 0.0
                rec.bounceTimes = lf["block.times"]?.let { dbl(run, it, ctx).toInt() } ?: 0
            }
            rec.fused = lf["block.fused"]?.let { any(run, it, ctx) } == true
            rec.reflect = lf["hit.reflect"]?.let { any(run, it, ctx) } == true
            rec.stickTicks = stuck
            rec.attachTo = lf["hit.attach_to"]?.let { strOf(run, it, ctx) }
            rec.attachTicks = lf["hit.attach.for"]?.let { dbl(run, it, ctx).toInt() } ?: 0
            val once = lf["hit.once"]?.let { any(run, it, ctx) } == true
            rec.hitTimes = if (once) 1 else lf["hit.times"]?.let { dbl(run, it, ctx).toInt() } ?: 1
            rec.endsOn = buildSet {
                // a reflecting body bounces off its victim: the hit does not end the flight
                if (!rec.reflect && lf["hit.once"]?.let { any(run, it, ctx) } == true) add("hit")
                // a fused stick holds to the flight end: its block does not end the body, the End row at the fuse tick does
                if (mode == "despawn" || (mode == "stick" && !rec.fused)) add("block")
            }
        }
        val world = worldOf(run, rec, local, ctx.binding, now)
        val pose = visualPose(rec, world)
        val cast = castRef(run)
        val look = a.look
        val brightness = look.fields["brightness"]?.let { int(run, it as Val<Int>, ctx) }
        val glow = look.fields["glow"]?.let { strOf(run, it, ctx) }
        val view = DisplayView()
        val h: VisualHandle? = when (a.kind) {
            SpawnKind.FRAME, SpawnKind.PROJECTILE_BODY -> null
            SpawnKind.ITEM -> visual.spawnMesh(
                cast, req(run, look, "item", ctx), look.fields["part"]?.let { strOf(run, it, ctx) }, pose, brightness,
                MeshLook(
                    itemModel = look.fields["model"]?.let { strOf(run, it, ctx) },
                    shaderEffect = look.fields["shader_effect"]?.let { strOf(run, it, ctx) },
                    mode = look.fields["mode"]?.let { DisplayItemMode.valueOf(strOf(run, it, ctx).uppercase()) } ?: DisplayItemMode.FIXED,
                    rigShading = look.fields["rig_shading"]?.let { any(run, it, ctx) as? Boolean } ?: true,
                ),
                view = view,
                glow = glow,
            )
            SpawnKind.BLOCK -> visual.spawnBlock(cast, req(run, look, "block", ctx), pose, brightness, view = view, glow = glow)
            SpawnKind.TEXT -> visual.spawnText(
                cast, req(run, look, "text", ctx), look.fields["background"]?.let { strOf(run, it, ctx) } ?: "#00000000",
                flag(run, look, "shadow", ctx), flag(run, look, "see_through", ctx), pose, brightness, view,
            )
            SpawnKind.SPRITE -> visual.spawnPlate(
                cast, req(run, look, "sprite", ctx), look.fields["alpha"]?.let { dbl(run, it, ctx) } ?: 1.0,
                look.fields["color"]?.let { strOf(run, it, ctx) }, flag(run, look, "see_through", ctx), pose, brightness, view = view, glow = glow,
            )
            SpawnKind.RIBBON -> if (strOf(run, look.fields["shape"] ?: throw IllegalArgumentException("ribbon look needs 'shape'"), ctx) == "line") {
                // ADR-0303 section 5 `shape: line`: both ends are live anchors read again every tick (lineStep), so the thread follows its frames
                val argb = look.fields["argb"]?.let { dbl(run, it, ctx).toLong().toInt() } ?: -1
                val fromCell = strOf(run, look.fields["from"] ?: throw IllegalArgumentException("line ribbon look needs 'from'"), ctx)
                val toCell = strOf(run, look.fields["to"] ?: throw IllegalArgumentException("line ribbon look needs 'to'"), ctx)
                // rev4.14 polyline: the vertex count, the re-jag epochs and the stream are fixed here; only the offsets are drawn (visual, from the row seed)
                val segments = look.fields["segments"]?.let { dbl(run, it, ctx).toInt() } ?: 1
                val jitter = if (segments > 1) LineJitter(
                    dbl(run, look.fields["jitter_spread"] ?: throw IllegalArgumentException("polyline ribbon look needs 'jitter_spread'"), ctx),
                    look.fields["jitter_along"]?.let { dbl(run, it, ctx) } ?: 0.0,
                    look.fields["jitter_every"]?.let { dbl(run, it, ctx).toInt() },
                    // follow: reuse the target line's seed (its spawn row's), so the two lines are one bolt; otherwise this row's own seed
                    look.fields["jitter_follow"]?.let { followSeed(run, row, strOf(run, it, ctx)) } ?: ctx.scope.seed,
                ) else null
                val rec = LineRec(fromCell, toCell, ctx.scope, ctx.binding, ctx.owner, ctx.level, ctx.base, segments, jitter, now)
                visual.spawnLine(
                    cast, lineVertices(run, rec, ctx, now), dbl(run, look.fields["thickness"] ?: throw IllegalArgumentException("line ribbon look needs 'thickness'"), ctx),
                    "#%06x".format(argb and 0xFFFFFF), ((argb ushr 24) and 0xFF) / 255.0,
                ).also { run.lines[id] = rec }
            } else {
                // the lowered ribbon look (RibbonOpSpec): `argb` is #aarrggbb and its alpha is the trail opacity (ADR-0303 rev4.4)
                val argb = look.fields["argb"]?.let { dbl(run, it, ctx).toLong().toInt() } ?: -1
                val width = look.fields["width_frac"] ?: look.fields["width"]
                visual.spawnTrail(
                    cast, dbl(run, look.fields["history_ticks"] ?: throw IllegalArgumentException("ribbon look needs 'history_ticks'"), ctx).toInt(),
                    width?.let { dbl(run, it, ctx) } ?: 1.0, "#%06x".format(argb and 0xFFFFFF), ((argb ushr 24) and 0xFF) / 255.0,
                ).also { th ->
                    visual.setTrailLook(th, look.fields["fade"]?.let { dbl(run, it, ctx) } ?: 1.0, look.fields["style"]?.let { strOf(run, it, ctx) } ?: "soft")
                }
            }
        }
        val gh = if (h != null) GatewayHandle(h.id) else GatewayHandle(nextSynthetic--)
        rec.real = h
        rec.anchor?.let { run.recs[it] }?.let { p -> rec.parentSeen = worldOf(run, p, p.localAt(now), null, now) }
        // a glyph quad: the text colour and opacity of the spawn row are written like a first `set` (spawnText has no slot for them)
        if (h != null && a.kind == SpawnKind.TEXT) {
            look.fields["color"]?.let { hooks.setDisplayText(h, "color", strOf(run, it, ctx)) }
            look.fields["text_opacity"]?.let { op -> dbl(run, op, ctx).takeIf { it < 255.0 }?.let { hooks.setDisplayField(h, "text_opacity", it / 255.0) } }
        }
        run.cursor.instances[id] = gh
    }

    private fun key(run: Run, a: Action.Key, ctx: RowCtx, now: Int) {
        val rec = run.recs[a.display] ?: return
        fun stretchCell(v: Val<*>): String? = (v as? Val.Live)?.name?.takeIf { it.startsWith("stretch:") }
        val stretch = (stretchCell(a.translation) ?: stretchCell(a.leftRotation) ?: stretchCell(a.scale))?.let { stretchOf(run, it, ctx) }
        val translation = if (stretch != null && stretchCell(a.translation) != null) localPoint(run, rec, stretch.base, ctx, now)
        else (a.translation as? Val.Live)?.takeIf { it.name.startsWith("follow:") }?.let { followOffset(run, rec, it.name, ctx, now) } ?: vec(run, a.translation, ctx)
        val fullCell = (a.leftRotation as? Val.Live)?.name
        // `<align cell>#x,y,z,w`: the turns listed after the align (a spin about the aligned axis, a tilt) ride on top of the live rotation
        val post = fullCell?.let { AlignCell.post(it) }
        val rotCell = if (post != null) AlignCell.bare(fullCell) else fullCell
        val leftRot: Rot = if (post != null) Val.Live(rotCell!!, null) else a.leftRotation
        val rotation = if (stretch != null && stretchCell(a.leftRotation) != null) {
            rec.alignFrom = null
            stretchRot(run, rec, stretch, ctx, now)
        } else if (rotCell != null && rotCell.startsWith("align:") && '@' in rotCell) blendedAlign(run, rec, rotCell, ctx, now) else {
            rec.alignFrom = null
            quat(run, leftRot, ctx, rec.anchor, rec.anchorName)
        }
        if (post != null) rotation.mul(post)
        val scale = if (stretch != null && stretchCell(a.scale) != null) stretch.scale() else vec(run, a.scale, ctx)
        val target = PoseV(translation, rotation, scale)
        rec.moveTo(target, now, a.durationTicks)
        rec.real?.let { visual.write(it, visualPose(rec, worldOf(run, rec, target, ctx.binding, now)), a.durationTicks) }
    }

    /**
     * `align:<axis>><to>^<up>@<w>` (law `align` with `ease`): slerp from the rotation the instance held when its blend window began to the
     * aligned one, by the weight [w]. The first `@` row of a window captures that start (the plain cell that ends the window clears it).
     */
    private fun blendedAlign(run: Run, rec: Rec, cell: String, ctx: RowCtx, now: Int): Quaterniond {
        val w = cell.substringAfterLast('@').toDouble()
        val from = rec.alignFrom ?: Quaterniond(rec.localAt(now).rot).also { rec.alignFrom = it }
        val aligned = alignRot(run, cell.substringBeforeLast('@'), ctx, rec.anchor, rec.anchorName)
        return Quaterniond(from).slerp(aligned, w)
    }

    /** `follow:<anchor>` (law `follow`): the local translation, in the rider's parent space, that puts the rider on the followed anchor's world position. */
    private fun followOffset(run: Run, rec: Rec, cell: String, ctx: RowCtx, now: Int): Vector3d =
        localPoint(run, rec, liveVec(run, cell.removePrefix("follow:"), ctx), ctx, now)

    /** The rider's parent pose: its anchor instance, else the named / document anchor, else the world. */
    private fun parentPose(run: Run, rec: Rec, ctx: RowCtx, now: Int): PoseV =
        rec.anchor?.let { run.recs[it] ?: run.finished[it] }?.let { worldOf(run, it, it.localAt(now), ctx.binding, now) }
            ?: if (rec.anchorName.isNotEmpty() && rec.anchorName != "world") PoseV(namedVec(run, rec.anchorName, ctx.binding, ctx.scope), namedRot(run, rec.anchorName, ctx.binding, ctx.scope), Vector3d(1.0, 1.0, 1.0)) else PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0))

    /** The local translation, in the rider's parent space, of the world point [world]. */
    private fun localPoint(run: Run, rec: Rec, world: Vector3d, ctx: RowCtx, now: Int): Vector3d {
        val parent = parentPose(run, rec, ctx, now)
        return Vector3d(world).sub(parent.pos).rotate(Quaterniond(parent.rot).conjugate())
    }

    /** `stretch:<base>><tip>^<ax>,<ay>,<az>` (law `stretch`) read at one tick: both ends in world space and the object axis that spans them. */
    private class Stretch(val base: Vector3d, val tip: Vector3d, val axis: Vec3) {
        val length: Double get() = Vector3d(tip).sub(base).length()

        /** [length] along [axis], 1 on the other axes (a display's unit length is one block). */
        fun scale(): Vector3d = Vector3d(
            1.0 + (length - 1.0) * Math.abs(axis.x), 1.0 + (length - 1.0) * Math.abs(axis.y), 1.0 + (length - 1.0) * Math.abs(axis.z),
        )
    }

    /** Resolves both ends through [liveVec], so builtin anchors, skill-declared anchors and instance anchors answer the same on every host. */
    private fun stretchOf(run: Run, cell: String, ctx: RowCtx): Stretch {
        val body = cell.removePrefix("stretch:")
        val ends = body.substringBeforeLast('^')
        val a = body.substringAfterLast('^').split(',').map { it.toDouble() }
        return Stretch(liveVec(run, ends.substringBefore('>'), ctx), liveVec(run, ends.substringAfter('>'), ctx), Vec3(a[0], a[1], a[2]))
    }

    /** The rotation that carries the object's [axis] onto +Z (the forward that [Basis.facing] aims). */
    private fun axisToZ(axis: Vec3): Quaternion {
        val z = Vec3(0.0, 0.0, 1.0)
        val a = axis.normalize()
        return when {
            a.dot(z) > 1.0 - 1e-12 -> Quaternion.IDENTITY
            a.dot(z) < -1.0 + 1e-12 -> Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.PI)
            else -> Quaternion.axisAngle(a.cross(z), Math.acos(a.dot(z).coerceIn(-1.0, 1.0)))
        }
    }

    /** The parent frame's forward in world; a facing along its up rolls toward it (+Z when there is no frame). */
    private fun parentForward(parentRot: Quaterniond): Vec3 {
        val f = Vector3d(0.0, 0.0, 1.0).rotate(parentRot)
        return Vec3(f.x, f.y, f.z)
    }

    /**
     * The local rotation that points the object's axis from the base to the tip, rolled with its up toward the parent frame's up (world up
     * when there is no frame); a span along that up rolls toward the parent frame's forward instead, so a vertical stretch is the identity
     * in its frame. A zero-length stretch keeps the held rotation.
     */
    private fun stretchRot(run: Run, rec: Rec, st: Stretch, ctx: RowCtx, now: Int): Quaterniond {
        val d = Vector3d(st.tip).sub(st.base)
        if (d.length() < 1e-9) return Quaterniond(rec.localAt(now).rot)
        val parentRot = parentPose(run, rec, ctx, now).rot
        val up = Vector3d(0.0, 1.0, 0.0).rotate(parentRot)
        val w = Basis.facing(Vec3(d.x, d.y, d.z), Vec3(up.x, up.y, up.z), parentForward(parentRot)) * axisToZ(st.axis)
        return Quaterniond(parentRot).conjugate().mul(Quaterniond(w.x, w.y, w.z, w.w)).normalize()
    }

    private fun teleport(run: Run, a: Action.Teleport, ctx: RowCtx, now: Int) {
        val rec = run.recs[a.display] ?: return
        val f = Frame.fromYawPitch(Vec3.ZERO, num(run, a.yaw, ctx), num(run, a.pitch, ctx)).rotation
        val target = PoseV(vec(run, a.position, ctx), Quaterniond(f.x, f.y, f.z, f.w), Vector3d(rec.to.scale))
        rec.moveTo(target, now, a.durationTicks)
        rec.real?.let { visual.write(it, visualPose(rec, worldOf(run, rec, target, ctx.binding, now)), a.durationTicks) }
    }

    private fun trailPoint(run: Run, a: Action.TrailPoint, ctx: RowCtx, now: Int) {
        val rec = run.recs[a.ribbon] ?: return
        val h = rec.real ?: return
        val tip = vec(run, a.position, ctx)
        val root = worldOf(run, rec, rec.localAt(now), ctx.binding, now).pos
        visual.pushTrailPoint(h, Vec3(root.x, root.y, root.z), Vec3(tip.x, tip.y, tip.z))
    }

    private fun particle(run: Run, row: Row, a: Action.Particle, ctx: RowCtx) {
        val pos = vec(run, a.position, ctx)
        val off = vec(run, a.offset, ctx)
        val req = run.request
        particles.emit(
            castRef(run),
            ParticleBurst(
                particle = a.type, at = Vec3(pos.x, pos.y, pos.z), count = run.fields[row.instance]?.get("count")?.toInt() ?: int(run, a.count, ctx), offset = Vec3(off.x, off.y, off.z),
                speed = num(run, a.extra, ctx), data = a.data, dir = null, spreadDeg = 0.0, inheritVelocity = null,
                look = Frame.fromYawPitch(Vec3.ZERO, req.casterYawDeg, req.casterPitchDeg).forward(),
                seed = rowStream(run, row, ctx).nextLong(),
            ),
        )
    }

    private fun sound(run: Run, row: Row, a: Action.Sound, ctx: RowCtx) {
        val p = vec(run, a.position, ctx)
        val volume = run.fields[row.instance]?.get("volume") ?: num(run, a.volume, ctx)
        sounds.play(castRef(run), a.sound, Vec3(p.x, p.y, p.z), a.category, volume, num(run, a.pitch, ctx))
    }

    /** A named custom particle definition at a position; `count` is the definition's own unless a `set` row overrode it. */
    private fun particleDef(run: Run, row: Row, a: Action.ParticleDef, ctx: RowCtx) {
        val pos = vec(run, a.position, ctx)
        val req = run.request
        particles.emit(
            castRef(run),
            ParticleBurst(
                particle = a.def, at = Vec3(pos.x, pos.y, pos.z), count = run.fields[row.instance]?.get("count")?.toInt() ?: a.count?.let { int(run, it, ctx) }, offset = Vec3.ZERO,
                speed = 0.0, data = null, dir = null, spreadDeg = 0.0, inheritVelocity = null,
                look = Frame.fromYawPitch(Vec3.ZERO, req.casterYawDeg, req.casterPitchDeg).forward(),
                seed = rowStream(run, row, ctx).nextLong(),
            ),
        )
    }

    /** One `set` row (item 15c): `count` / `volume` are folded into the instance's later particle / sound rows, `text_opacity` goes to the display. */
    private fun set(run: Run, row: Row, a: Action.Set, ctx: RowCtx, now: Int, src: String) {
        if (a.field == "color" || a.field == "text") {
            val rec = run.recs[row.instance]
            val handle = rec?.real
            if (rec != null && rec.kind != SpawnKind.TEXT && rec.kind != SpawnKind.SPRITE) {
                fault(run, now, src, "set ${a.field}: '${row.instance.text}' is a ${rec.kind.name.lowercase()} display; it is for text and sprite displays")
            } else if (handle == null) fault(run, now, src, "set ${a.field}: '${row.instance.text}' has no display handle")
            else if (!hooks.setDisplayText(handle, a.field, strOf(run, a.value, ctx))) fault(run, now, src, "set ${a.field}: no display sink in PlayerHooks")
            return
        }
        val value = dbl(run, a.value, ctx)
        when (a.field) {
            "width", "width_frac" -> {
                val rec = run.recs[row.instance]
                val handle = rec?.real
                if (rec != null && rec.kind != SpawnKind.RIBBON) fault(run, now, src, "set ${a.field}: '${row.instance.text}' is a ${rec.kind.name.lowercase()} display; it is for ribbons")
                else if (handle != null) visual.setTrailWidth(handle, value)
            }
            "count", "volume" -> run.fields.getOrPut(row.instance) { HashMap() }[a.field] = value
            "text_opacity" -> {
                val rec = run.recs[row.instance]
                val handle = rec?.real
                if (rec != null && rec.kind != SpawnKind.TEXT && rec.kind != SpawnKind.SPRITE) {
                    fault(run, now, src, "set text_opacity: '${row.instance.text}' is a ${rec.kind.name.lowercase()} display; fade is for text and sprite displays (use grow on item / block)")
                } else if (handle == null) fault(run, now, src, "set text_opacity: '${row.instance.text}' has no display handle")
                else if (!hooks.setDisplayField(handle, a.field, value)) fault(run, now, src, "set text_opacity: no display sink in PlayerHooks")
            }
            else -> fault(run, now, src, "set: unknown field '${a.field}'")
        }
    }

    // hit: broad phase from the query, narrow phase from the domain resolver, swept along the frame's last-tick displacement (no tunnelling).
    // Victims are ordered nearest-first from the shape origin, cut to targets.maxCount, damaged (every amount read before any damage lands), then the
    // `<op>.hit` handlers start (one event per victim, index = victim ordinal). `<op>.miss` fires once, on the op's final detection, if nothing was ever hit.
    private fun hit(run: Run, row: Row, a: Action.Hit, ctx: RowCtx, now: Int) {
        val caster = run.request.caster
        // frozen targets are skipped: `if (isFrozen(...)) continue`
        a.anchor?.let { if (isFrozen(run, it, now)) return }
        val delta = a.anchor?.let { run.recs[it] }?.let { r ->
            val cur = worldOf(run, r, r.localAt(now), ctx.binding, now).pos
            val prev = worldOf(run, r, r.prevLocal(now), ctx.binding, now - 1).pos
            Vector3d(cur).sub(prev)
        } ?: Vector3d()
        fun back(p: Vector3d, k: Double) = Vec3(p.x - delta.x * k, p.y - delta.y * k, p.z - delta.z * k)
        var sweepRadius: Double
        val origin: Vector3d
        val volumes: (Double) -> ResolvedVolume = when (val s = a.shape) {
            is HitShape.Sphere -> {
                val c = vec(run, s.center, ctx)
                val r = num(run, s.radius, ctx)
                sweepRadius = r
                origin = c
                ({ k -> ResolvedVolume.Sphere(back(c, k), r) })
            }
            is HitShape.Capsule -> {
                val f = vec(run, s.from, ctx)
                val t = vec(run, s.to, ctx)
                val r = num(run, s.radius, ctx)
                sweepRadius = r
                origin = f
                ({ k -> ResolvedVolume.Capsule(back(f, k), back(t, k), r) })
            }
            is HitShape.Sector -> {
                val o = vec(run, s.origin, ctx)
                val raw = vec(run, s.forward, ctx)
                if (s.flat) raw.y = 0.0
                if (raw.lengthSquared() < 1e-12) throw IllegalArgumentException("sector forward has no horizontal component (${if (s.flat) "forward: yaw" else "forward: look"} of a vertical anchor)")
                val fwd = raw.normalize()
                val up = if (kotlin.math.abs(fwd.y) > 0.999) Vector3d(0.0, 0.0, -1.0) else Vector3d(0.0, 1.0, 0.0)
                // the domain derives up = forward x left, so left = up x forward
                val left = Vector3d(up).cross(fwd).normalize()
                val deg = num(run, s.deg, ctx)
                val range = num(run, s.range, ctx)
                val height = num(run, s.height, ctx)
                sweepRadius = range
                origin = o
                ({ k ->
                    ResolvedVolume.Sector(back(o, k), Vec3(fwd.x, fwd.y, fwd.z), Vec3(left.x, left.y, left.z), deg, range, height)
                })
            }
            is HitShape.Box -> {
                val c = vec(run, s.center, ctx)
                val h = vec(run, s.halfExtents, ctx)
                val rot = quat(run, s.rotation, ctx)
                sweepRadius = min(h.x, min(h.y, h.z))
                origin = c
                ({ k -> ResolvedVolume.Box(back(c, k), Vec3(h.x, h.y, h.z), Quaterniond(rot)) })
            }
        }
        // item 14.7: poses = min(maxSweepSteps, ceil(travel / radius) + 1); the last one is the current tick
        val travel = delta.length()
        val poses = if (travel < 1e-9) 1 else min(config.maxSweepSteps.toDouble(), ceil(travel / max(sweepRadius, 1e-6)) + 1.0).toInt().coerceAtLeast(1)
        // hit state belongs to the op, not to one occurrence of it: a repeating hit shares its `once` / `every` window and its one event path
        val opKey = InstanceId(row.instance.path.dropLast(1) + row.instance.path.last().copy(index = null))
        val once = run.hitOnce.getOrPut(opKey) { HashSet() }
        val last = run.hitLast.getOrPut(opKey) { HashMap() }
        val window = a.window
        fun blocked(victim: UUID): Boolean = when (window) {
            HitWindow.Once -> victim in once
            is HitWindow.Every -> last[victim]?.let { now - it < window.ticks } ?: false
        }
        val seen = LinkedHashMap<UUID, Pair<Vec3, Vec3>>()
        val feetOf = HashMap<UUID, Vec3>()
        for (i in 0 until poses) {
            val k = if (poses == 1) 0.0 else (poses - 1 - i).toDouble() / (poses - 1)
            val vol = volumes(k)
            trace.hitVolume(run.cursor.handle, row.instance, vol, now)
            val cands = query.nearby(castRef(run), vol.broadCenter, vol.broadRadius, caster).filter { it.entityId !in seen }
            for (c in cands) feetOf[c.entityId] = c.feet
            for (ce in resolver.narrow(row.instance.text, now, row.instance.text, vol, Vec3(delta.x, delta.y, delta.z), cands)) {
                if (blocked(ce.victim) || !targetKindOk(run, a.targets.kind, ce.victim)) continue
                seen.putIfAbsent(ce.victim, ce.pointWorld to ce.normalWorld)
            }
        }
        val originVec = Vec3(origin.x, origin.y, origin.z)
        val nearest = seen.entries
            .sortedWith(compareBy({ feetOf.getValue(it.key).distance(originVec) }, { it.key }))
            .let { all -> a.targets.maxCount?.let { all.take(it) } ?: all }
        val hitPath = EventPath(opKey.text + ".hit")
        // a victim whose `absorb: true` transition takes the hit is not hit: no damage, no `hit` (an `absorbed` event instead), nothing counted
        val absorbedPath = EventPath(opKey.text + ".absorbed")
        val absorbed = HashSet<UUID>()
        val all = nearest.mapIndexed { idx, e ->
            val contact = ContactInfo(Vector3d(e.value.first.x, e.value.first.y, e.value.first.z), Vector3d(e.value.second.x, e.value.second.y, e.value.second.z))
            if (absorbedBy(run, e.key, now)) {
                absorbed += e.key
                e.key to EventBinding(absorbedPath, idx, contact, EntityRef(e.key))
            } else e.key to EventBinding(hitPath, idx, contact, EntityRef(e.key))
        }
        val bindings = all.filter { it.first !in absorbed }
        // every amount is read first (so a `stacks(...)` read sees the stacks as they were before any damage or consume), then the damage lands
        val amounts = bindings.map { (_, b) ->
            val vctx = RowCtx(ctx.base, b, ctx.owner, ctx.level, ctx.scope)
            a.damage.map { c -> SkillDamage(c.attribute.name.lowercase(), num(run, c.amount, vctx)) }
        }
        for ((n, pair) in bindings.withIndex()) {
            val victim = pair.first
            once += victim
            last[victim] = now
            val comps = amounts[n]
            if (comps.isNotEmpty() && gameplay.damage(run.cursor.skill.def.id, caster, victim, comps, seen.getValue(victim).first, a.ignoreInvulnerability, false)) run.killed += victim
        }
        for ((v, b) in all) {
            if (v in absorbed) {
                once += v
                last[v] = now
            }
            run.pending += RuntimeEvent(b.event, now, b.index, b.contact, b.victim)
        }
        if (bindings.isNotEmpty()) run.hitEver += opKey
        else if (a.finalDetection && opKey !in run.hitEver && run.missFired.add(opKey)) {
            run.pending += RuntimeEvent(EventPath(opKey.text + ".miss"), now, null, null, null)
        }
    }

    /** The body's flight from its look (item 3.6): a constant `speed` or the ramp `speed.from/to/for/ease`, along `velocity`. */
    private fun flightOf(run: Run, look: Look, local: PoseV, ctx: RowCtx): Flight? {
        val f = look.fields
        val velocity = f["velocity"]?.let { any(run, it, ctx) as? Vector3d } ?: return null
        if (velocity.lengthSquared() < 1e-18) return null
        val speed = f["speed"]?.let { ProjectileKinematics.constant(dbl(run, it, ctx)) }
            ?: f["speed.to"]?.let { to ->
                val ease = f["speed.ease"]?.let { strOf(run, it, ctx) } ?: "linear"
                ProjectileKinematics.Speed(
                    f["speed.from"]?.let { dbl(run, it, ctx) } ?: dbl(run, to, ctx), dbl(run, to, ctx),
                    f["speed.for"]?.let { dbl(run, it, ctx).toInt() } ?: 0,
                    Easing.fromKey(ease) ?: throw IllegalArgumentException("projectile speed.ease '$ease' is not an easing"),
                )
            } ?: ProjectileKinematics.constant(velocity.length()) // speed absent = |velocity| (3.6)
        val gravity = f["gravity"]?.let { dbl(run, it, ctx) } ?: 0.0
        val drag = f["drag"]?.let { dbl(run, it, ctx) } ?: 0.0
        return Flight(Vector3d(velocity).normalize(), speed, Vector3d(local.pos), gravity, drag)
    }

    private fun projectileStep(run: Run, now: Int) {
        for ((id, rec) in run.recs.entries.toList()) {
            if (rec.kind != SpawnKind.PROJECTILE_BODY) continue
            // a frozen body holds its place: its flight clock and range timer stand still, no contact is tested
            if (isFrozen(run, id, now)) {
                rec.frozenTicks++
                if (rec.expireTick >= 0) rec.expireTick++
                continue
            }
            rec.flight?.let { fl ->
                val cur = rec.localAt(now)
                rec.moveTo(PoseV(fl.at(now - rec.spawnTick - rec.frozenTicks - rec.clockBase), cur.rot, cur.scale), now, 0)
            }
            if (rec.stuckUntil >= 0) {
                // stuck (a block in stick mode, or `hit.attach` on a victim): the body holds its place / rides its anchor until `unstick`, then the flight ends
                // a body riding a victim that is gone (died, left the world) ends with it
                val rider = rec.binding?.victim
                val victimGone = rider != null && rec.attachTo != null && query.rootOf(castRef(run), rider.id) == null
                if (rec.stuckUntil == now || victimGone) {
                    run.pending += RuntimeEvent(EventPath(id.text + ".unstick"), now, id.path.lastOrNull()?.index, null, null)
                    endByContact(run, id, rec, now)
                }
                continue
            }
            val blocked = blockContact(run, id, rec, now)
            if (!blocked) entityContact(run, id, rec, now)
            // R16: `expire` is the end by range / `for`, just before `end`; a hit / block end (rec.ended) never expires (checked at delivery)
            if (rec.expireTick == now) run.pending += RuntimeEvent(EventPath(id.text + ".expire"), now, id.path.lastOrNull()?.index, expireContact(run, rec, now), null)
        }
    }

    /** `contact` of an `expire` handler: where the body is when its range / `for` runs out (the end of the flight), facing up. */
    private fun expireContact(run: Run, rec: Rec, now: Int): ContactInfo {
        val w = worldOf(run, rec, rec.localAt(now), null, now).pos
        return ContactInfo(Vector3d(w.x, w.y, w.z), Vector3d(0.0, 1.0, 0.0))
    }

    /** `isFrozen`: the instance or any frame it rides is inside its `freeze` window. */
    private fun isFrozen(run: Run, id: InstanceId, now: Int): Boolean {
        if (run.frozenUntil.isEmpty()) return false
        var cur: InstanceId? = id
        var depth = 0
        while (cur != null && depth++ < 16) {
            if ((run.frozenUntil[cur] ?: Int.MIN_VALUE) > now) return true
            cur = run.recs[cur]?.anchor
        }
        return false
    }

    /**
     * A block on this tick's segment. despawn / stick fire `<body>.block` (when listened) and end / stop the flight; true when that block ends the flight, so no
     * entity is tested behind it. bounce never ends the flight: [bounceOff] reflects the velocity (the same path in the sim and on Paper).
     */
    private fun blockContact(run: Run, id: InstanceId, rec: Rec, now: Int): Boolean {
        val path = EventPath(id.text + ".block")
        if (!rec.bounceMode && path !in run.listened) return false
        val cur = worldOf(run, rec, rec.localAt(now), null, now).pos
        val prev = worldOf(run, rec, rec.poseAtEndOf(now - 1), null, now - 1).pos
        if (cur.distanceSquared(prev) < 1e-12) return false
        val b = query.raycastBlock(castRef(run), Vec3(prev.x, prev.y, prev.z), Vec3(cur.x, cur.y, cur.z)) ?: return false
        val contact = ContactInfo(Vector3d(b.point.x, b.point.y, b.point.z), Vector3d(b.normal.x, b.normal.y, b.normal.z))
        if (rec.bounceMode) {
            bounceOff(run, id, rec, now, Vector3d(cur).sub(prev), contact, path)
            return false
        }
        run.pending += RuntimeEvent(path, now, id.path.lastOrNull()?.index, contact, null)
        if (rec.blockDespawn) endByContact(run, id, rec, now)
        if (rec.stickTicks > 0 || rec.fused) stick(run, id, rec, now, null)
        return "block" in rec.endsOn
    }

    /**
     * `block.mode bounce`: the velocity [v] (the last tick's displacement) is reflected at [contact]: the normal part comes back scaled by `restitution`, the
     * tangent part keeps `1 - friction`. The first `times` such contacts are counted bounces (`<body>.block` then `<body>.bounce`); a later contact, or one whose
     * rebound would be below [REST_SPEED], only rolls: the normal part is dropped, friction applies, no event. The body never ends here, only at its `for`.
     */
    private fun bounceOff(run: Run, id: InstanceId, rec: Rec, now: Int, v: Vector3d, contact: ContactInfo, blockPath: EventPath) {
        val n = Vector3d(contact.normal)
        if (n.lengthSquared() < 1e-12) return
        n.normalize()
        val vn = v.x * n.x + v.y * n.y + v.z * n.z
        if (vn >= 0.0) return
        val keep = 1.0 - rec.friction
        val rebound = -vn * rec.restitution
        val counted = rec.bounces < rec.bounceTimes && rebound >= REST_SPEED
        val along = if (counted) rebound else 0.0
        val out = Vector3d(v.x - vn * n.x, v.y - vn * n.y, v.z - vn * n.z).mul(keep).add(n.x * along, n.y * along, n.z * along)
        rebase(rec, now, Vector3d(contact.point).add(n.x * CONTACT_SKIN, n.y * CONTACT_SKIN, n.z * CONTACT_SKIN), out)
        if (!counted) return
        rec.bounces++
        // `event.i` of a bounce is its ordinal (0-based) on a single body; a family member keeps its family index
        val index = id.path.lastOrNull()?.index ?: (rec.bounces - 1)
        val bouncePath = EventPath(id.text + ".bounce")
        val tables = run.cursor.skill.table.subTables
        if (blockPath in run.listened || blockPath in tables) run.pending += RuntimeEvent(blockPath, now, index, contact, null)
        if (bouncePath in run.listened || bouncePath in tables) run.pending += RuntimeEvent(bouncePath, now, index, contact, null)
    }

    /** The body continues from [pos] with the per-tick velocity [vel]: a fresh flight (same gravity / drag) whose clock starts at this tick. */
    private fun rebase(rec: Rec, now: Int, pos: Vector3d, vel: Vector3d) {
        val old = rec.flight ?: return
        val speed = vel.length()
        val dir = if (speed < 1e-9) Vector3d(0.0, -1.0, 0.0) else Vector3d(vel).mul(1.0 / speed)
        rec.flight = Flight(dir, ProjectileKinematics.constant(speed), Vector3d(pos), old.gravity, old.drag)
        val cur = rec.localAt(now)
        rec.moveTo(PoseV(Vector3d(pos), cur.rot, cur.scale), now, 0)
        rec.clockBase = now - rec.spawnTick - rec.frozenTicks
    }

    /**
     * `block: {mode: stick}` and `hit: {attach: {for: N}}` share one state: the flight stops, `<body>.stick` fires, [Rec.stickTicks] (block) or [Rec.attachTicks] (victim) later `<body>.unstick`
     * fires and the flight ends. With a [victim] the body is mounted on the author-named `hit.attach_to` anchor of that entity (the offset it had at the
     * hit is kept), so it rides the victim; with none it holds its place.
     */
    private fun stick(run: Run, id: InstanceId, rec: Rec, now: Int, victim: EventBinding?) {
        val at = worldOf(run, rec, rec.localAt(now), null, now)
        rec.flight = null
        // fused: the body stays until its flight end (the End row at the fixed fuse tick), which is neither an unstick nor an expire
        if (rec.fused) {
            rec.stuckUntil = Int.MAX_VALUE
            rec.expireTick = -1
        } else rec.stuckUntil = now + (if (victim?.victim != null && rec.attachTo != null) rec.attachTicks else rec.stickTicks)
        val anchor = rec.attachTo
        if (victim?.victim != null && anchor != null) {
            rec.binding = victim
            rec.anchorName = anchor
            val scope = RowScope.cast(run.request.seed)
            val pp = namedVec(run, anchor, victim, scope)
            val pr = namedRot(run, anchor, victim, scope)
            val local = PoseV(Vector3d(at.pos).sub(pp).rotate(Quaterniond(pr).invert()), Quaterniond(pr).invert().mul(at.rot), Vector3d(at.scale))
            rec.moveTo(local, now, 0)
        } else rec.moveTo(PoseV(at.pos, at.rot, at.scale), now, 0)
        run.pending += RuntimeEvent(EventPath(id.text + ".stick"), now, id.path.lastOrNull()?.index, victim?.contact, victim?.victim)
    }

    /** The flight ends at a contact (last victim, or a despawn block): `end` right after the contact event, the body goes at cleanup. */
    private fun endByContact(run: Run, id: InstanceId, rec: Rec, now: Int) {
        rec.ended = true
        run.pending += RuntimeEvent(EventPath(id.text + ".end"), now, id.path.lastOrNull()?.index, null, null)
        if (id !in run.pendingEnds) run.pendingEnds += id
    }

    /**
     * Fires `<body>.hit` (with `event.victim` and `contact`) for every victim the body's swept sphere reaches this tick, nearest the segment start first.
     * Projectile contact: the segment `from -> to` of one tick,
     * inflated by the hit radius (`radiusOf`), the owner never a victim (`ProjectileWorldProbe` contract, :52), each victim hit once.
     * `hit.once` ends the flight at the first victim (`.end` follows at once); otherwise it ends after `hit.times` victims.
     */
    private fun entityContact(run: Run, id: InstanceId, rec: Rec, now: Int) {
        if (rec.hitTimes <= 0 || now == rec.spawnTick) return
        val path = EventPath(id.text + ".hit")
        val absorbedPath = EventPath(id.text + ".absorbed")
        val tables = run.cursor.skill.table.subTables
        if (!rec.reflect && path !in run.listened && path !in tables && absorbedPath !in run.listened && absorbedPath !in tables) return
        if (rec.hitTargets == "blocks_only") return
        val cur = worldOf(run, rec, rec.localAt(now), null, now).pos
        val prev = worldOf(run, rec, rec.poseAtEndOf(now - 1), null, now - 1).pos
        val delta = Vec3(cur.x - prev.x, cur.y - prev.y, cur.z - prev.z)
        val start = Vec3(prev.x, prev.y, prev.z)
        val raw = ArrayList<ContactEvent>()
        fun test(vol: ResolvedVolume, velocity: Vec3) {
            trace.hitVolume(run.cursor.handle, id, vol, now)
            val cands = query.nearby(castRef(run), vol.broadCenter, vol.broadRadius, run.request.caster)
                .filter { it.entityId !in rec.hitVictims && targetKindOk(run, rec.hitTargets, it.entityId) }
            raw += resolver.narrow(id.text, now, id.text, vol, velocity, cands)
        }
        /** One capsule swept back along the last-tick displacement [d] of whatever carries it (the same poses rule as the `hit` op, item 14.7). */
        fun sweep(f: Vector3d, t: Vector3d, d: Vector3d) {
            val travel = d.length()
            val poses = if (travel < 1e-9) 1 else min(config.maxSweepSteps.toDouble(), ceil(travel / max(rec.shapeRadius, 1e-6)) + 1.0).toInt().coerceAtLeast(1)
            for (i in 0 until poses) {
                val k = if (poses == 1) 0.0 else (poses - 1 - i).toDouble() / (poses - 1)
                test(
                    ResolvedVolume.Capsule(
                        Vec3(f.x - d.x * k, f.y - d.y * k, f.z - d.z * k), Vec3(t.x - d.x * k, t.y - d.y * k, t.z - d.z * k), rec.shapeRadius,
                    ),
                    Vec3(d.x, d.y, d.z),
                )
            }
        }
        val self = rec.shapeSelf
        if (rec.shapeFrames.isEmpty() && self == null) test(ResolvedVolume.Capsule(start, Vec3(cur.x, cur.y, cur.z), rec.hitRadius), delta)
        if (self != null) {
            // E1: the body's own axis. Its points ride its live rotation; the displacement is the body's own, so the capsule is swept along the flight.
            val w = worldOf(run, rec, rec.localAt(now), null, now)
            val d = Vector3d(w.pos).sub(prev.x, prev.y, prev.z)
            fun at(name: String): Vector3d = Vector3d(rec.points.getValue(name)).rotate(w.rot).add(w.pos)
            sweep(at(self.from), at(self.to), d)
        }
        for (sf in rec.shapeFrames) {
            // R43: one swept capsule per rider frame, swept back along the frame's own last-tick displacement
            val fr = run.recs.entries.firstOrNull { (k, r) ->
                r.kind == SpawnKind.FRAME && k.path.size == id.path.size && k.path.dropLast(1) == id.path.dropLast(1) && k.path.last().op.value == sf.frame &&
                    k.path.last().index == id.path.last().index
            }?.value
            if (fr == null) {
                fault(run, now, id.text, "hit.shape frame '${sf.frame}' is not a live frame of this projectile")
                continue
            }
            val d = Vector3d(worldOf(run, fr, fr.localAt(now), null, now).pos).sub(worldOf(run, fr, fr.poseAtEndOf(now - 1), null, now - 1).pos)
            val f = live.vec(run.cursor.handle, sf.from, null, RowScope.cast(run.request.seed))
            val t = live.vec(run.cursor.handle, sf.to, null, RowScope.cast(run.request.seed))
            sweep(Vector3d(f.x, f.y, f.z), Vector3d(t.x, t.y, t.z), d)
        }
        val found = raw.distinctBy { it.victim }.sortedBy { it.pointWorld.distance(start) }
        // an absorbed contact (the victim's `absorb: true` transition) uses up a hit and ends the flight like any other, but raises `absorbed`, not `hit`
        val absorbedVictims = HashSet<UUID>()
        var reflected = false
        for (ce in found) {
            if (rec.hitTimes <= 0) break
            rec.hitVictims += ce.victim
            // hit.reflect: a victim never uses up the hit, the body bounces off it (once per victim, one rebound per tick)
            if (!rec.reflect) rec.hitTimes--
            if (rec.reflect && !reflected) {
                reflected = true
                val body = worldOf(run, rec, rec.localAt(now), null, now).pos
                val v = Vector3d(body).sub(prev.x, prev.y, prev.z)
                val n = Vector3d(ce.normalWorld.x, ce.normalWorld.y, ce.normalWorld.z)
                if (n.lengthSquared() < 1e-12) { n.x = -v.x; n.y = -v.y; n.z = -v.z }
                if (n.lengthSquared() > 1e-12) {
                    n.normalize()
                    if (n.x * v.x + n.y * v.y + n.z * v.z > 0.0) n.mul(-1.0)
                    bounceOff(run, id, rec, now, v, ContactInfo(Vector3d(body), n), EventPath(id.text + ".block"))
                }
            }
            val wasAbsorbed = absorbedBy(run, ce.victim, now)
            if (wasAbsorbed) absorbedVictims += ce.victim
            run.pending += RuntimeEvent(
                if (wasAbsorbed) absorbedPath else path, now, id.path.lastOrNull()?.index,
                ContactInfo(Vector3d(ce.pointWorld.x, ce.pointWorld.y, ce.pointWorld.z), Vector3d(ce.normalWorld.x, ce.normalWorld.y, ce.normalWorld.z)), EntityRef(ce.victim),
            )
        }
        if (rec.hitTimes <= 0 && found.isNotEmpty()) {
            val first = found.first()
            if (first.victim in absorbedVictims) endByContact(run, id, rec, now)
            else if (rec.attachTo != null) {
                val contact = ContactInfo(Vector3d(first.pointWorld.x, first.pointWorld.y, first.pointWorld.z), Vector3d(first.normalWorld.x, first.normalWorld.y, first.normalWorld.z))
                stick(run, id, rec, now, EventBinding(path, id.path.lastOrNull()?.index, contact, EntityRef(first.victim)))
            } else endByContact(run, id, rec, now)
        }
    }

    private fun damage(run: Run, row: Row, a: Action.Damage, ctx: RowCtx, now: Int) {
        val comps = a.components.map { c -> SkillDamage(c.attribute.name.lowercase(), liveAmount(run, c, ctx)) }
        for (v in ruleTargets(run, a.targets, ctx)) {
            // the event's contact point is where the bolt met the event's victim; a chain hop's victim is somebody else, hit where it stands
            val point = ctx.binding?.contact?.point?.takeIf { row.chain == null }?.let { Vec3(it.x, it.y, it.z) } ?: query.rootOf(castRef(run), v)?.feet ?: Vec3.ZERO
            if (gameplay.damage(run.cursor.skill.def.id, run.request.caster, v, comps, point, false, false)) run.killed += v
        }
    }

    /** A literal amount, or a `${p}` / `= expr` amount read live now (it may read `event.victim`, `contact.*`). */
    private fun liveAmount(run: Run, c: DamageComponent, ctx: RowCtx): Double {
        val base = c.base
        if (base is NumericValue.Cel) {
            val cell = if (base.script.startsWith("\${")) base.script else "= ${base.script}"
            return dbl(run, Val.Live<Any>(cell, null), ctx) * ((c.multiplier as? NumericValue.Literal)?.value ?: 1.0)
        }
        require(base is NumericValue.Literal && c.multiplier is NumericValue.Literal) { "damage component is not a literal or an expression" }
        return c.constantValue
    }

    private fun effect(run: Run, row: Row, a: Action.Effect, ctx: RowCtx, now: Int, src: String) {
        val p = a.payload
        val kind = p["kind"]?.let { strOf(run, it, ctx) } ?: a.op.value
        val victims = p["target"]?.let { v ->
            @Suppress("UNCHECKED_CAST")
            targets(run, v as Val<TargetSet>, ctx)
        } ?: listOf(run.request.caster)
        fun cell(key: String): Val<Any> = p[key] ?: throw IllegalArgumentException("effect '$kind' needs '$key'")
        fun d(key: String): Double = dbl(run, cell(key), ctx)
        fun s(key: String): String = strOf(run, cell(key), ctx)
        fun v(key: String): Vector3d = any(run, cell(key), ctx) as Vector3d
        val caster = run.request.caster
        when (kind) {
            "dash" -> dashStart(run, row, p, ctx, now, src)
            "dash.end" -> run.dash.end(row.instance.text)
            "reel" -> reelStart(run, row, p, ctx, now, src)
            "reel.stop" -> {
                run.reel.stop(row.instance.text)
                run.reels.remove(row.instance)
            }
            "rig" -> rigStart(run, row, p, ctx, now, src)
            "impulse" -> {
                val vv = v("vector")
                victims.forEach { gameplay.impulse(it, Vec3(vv.x, vv.y, vv.z)) }
            }
            "knockback" -> {
                // Knockback: impulse(direction * strength), the direction is not normalised
                val vv = v("direction")
                val k = d("strength")
                victims.forEach { gameplay.impulse(it, Vec3(vv.x * k, vv.y * k, vv.z * k)) }
            }
            "pull" -> {
                // Pull: the horizontal speed that rests after `distance` blocks (vanilla drag + gravity solved), along the flat direction, with `lift` up
                val vv = v("direction")
                val flat = kotlin.math.sqrt(vv.x * vv.x + vv.z * vv.z)
                val lift = d("lift")
                val speed = PullMath.speedFor(d("distance"), lift)
                if (speed > 0.0 && flat > 1.0e-9) victims.forEach { gameplay.impulse(it, Vec3(vv.x / flat * speed, lift, vv.z / flat * speed)) }
            }
            "resource" -> victims.forEach { gameplay.restoreResource(it, s("resource"), d("amount")) }
            "heal" -> victims.forEach { gameplay.restoreResource(it, "health", d("amount")) }
            "modify_attribute" -> {
                @Suppress("UNCHECKED_CAST")
                val dur = p["for"]?.let { int(run, it as Val<Int>, ctx) }
                victims.forEach { gameplay.modifyAttribute(caster, it, s("attribute"), s("operation"), d("amount"), dur) }
            }
            "cleanse" -> victims.forEach { gameplay.cleanse(it, any(run, cell("negative_only"), ctx) as Boolean) }
            "consume_item" -> {
                val item = s("item")
                val count = d("count").toInt()
                // consume_item: a zero count is a no-op
                if (count > 0) victims.forEach { items.consume(castRef(run), it, item, count) }
            }
            "consume_status" -> victims.forEach { gameplay.consumeStatus(castRef(run), it, s("status")) }
            "freeze" -> {
                val ticks = d("ticks").toInt()
                val entities = LinkedHashSet<UUID>()
                for (name in any(run, cell("scope"), ctx) as List<*>) when (val n = name.toString()) {
                    "caster" -> entities += caster
                    "event.victim" -> entities += targets(run, Val.Live("event.victim", null), ctx)
                    else -> {
                        // freeze splits the scope into entities and frames (`host.freezeFrames`);
                        // the frame half was cast-internal state (CastInstance.kt:539-541), so it stays the player's, not a gateway call
                        val target = run.recs.keys.firstOrNull { "instance:" + it.text == n || it.text == n }
                        if (target == null) fault(run, now, src, "freeze scope '$n': not a live instance")
                        else if (ticks > 0) run.frozenUntil[target] = now + ticks
                    }
                }
                if (ticks > 0 && entities.isNotEmpty()) gameplay.freezeEntities(entities.toList(), ticks)
            }
            "camera_shake" -> {
                val ticks = d("ticks").toInt()
                val dir = s("direction")
                // `from_hit` outside an on.hit handler is refused at load (CameraShakeOpSpec.lower), so a victim is bound here
                val from = if (dir == "from_hit") liveVec(run, "event.victim", ctx).let { Vec3(it.x, it.y, it.z) } else null
                victims.forEach { gameplay.cameraShake(castRef(run), it, d("intensity"), ticks, from) }
            }
            "stop_sound" -> victims.forEach { gameplay.stopSound(castRef(run), it, s("sound")) }
            "body" -> {
                val id = row.instance
                // `react player:` lowers to a body row with only=player: a mob victim plays the rest of the reaction and skips the clip, by name (a trace, not a warn)
                if (p["only"]?.let { strOf(run, it, ctx) } == "player" && victims.firstOrNull()?.let { gameplay.isPlayer(it) } != true) {
                    trace.reactClipSkipped(run.cursor.handle, id, now, victims.firstOrNull())
                    return
                }
                run.recs[id]?.let { endInstance(run, id) }
                // the clip lives as long as its instance: End / state exit / cast end all go through endInstance
                // the body instance rides the caster (feet, body yaw): the clip's named points are its model points, read through `<id>.<point>`
                // `target:` picks whose body plays it (the caster, or the victim / attacker of the event this op runs in); the instance rides that entity
                val who = victims.firstOrNull() ?: throw IllegalArgumentException("body: target resolved to no entity")
                val targetName = (p["target"] as? Val.Live)?.name ?: "caster"
                val bodyRec = Rec(SpawnKind.FRAME, null, if (targetName.startsWith("caster")) "caster" else if (targetName == "victim") "victim" else "world", now, ctx.owner, ctx.detached, PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0)))
                bodyRec.binding = ctx.binding
                run.recs[id] = bodyRec
                run.cursor.instances[id] = GatewayHandle(nextSynthetic--)
                val anim = s("anim")
                val speed = d("speed")
                run.bodyClips[id] = who to anim
                gameplay.bodyClip(anim)?.let { clip ->
                    bodyRec.body = BodyPoints(clip, gameplay.bodySkeleton(), gameplay.isSlimSkin(who), speed)
                    freshBody(bodyRec)
                }
                gameplay.playBodyClip(castRef(run), who, anim, speed, emptyList())
            }
            "held_model" -> {
                val id = row.instance
                run.recs[id]?.let { endInstance(run, id) }
                // like `body`: the swap lives as long as its instance (End / state exit / cast end all go through endInstance)
                run.recs[id] = Rec(SpawnKind.FRAME, null, "", now, ctx.owner, ctx.detached, PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0)))
                run.cursor.instances[id] = GatewayHandle(nextSynthetic--)
                run.heldModels += id
                gameplay.setHeldModel(castRef(run), caster, s("model"))
            }
            "screen_flash" -> {
                val color = p["color"]?.let { d("color").toInt() }
                victims.forEach { gameplay.screenFlash(castRef(run), it, s("overlay"), color, d("fade_in").toInt(), d("stay").toInt(), d("fade_out").toInt()) }
            }
            "screen_veil" -> {
                val id = row.instance
                run.recs[id]?.let { endInstance(run, id) }
                // like `held_model`: the quads live as long as their instance (End at `ticks` / state exit / cast end go through endInstance)
                run.recs[id] = Rec(SpawnKind.FRAME, null, "", now, ctx.owner, ctx.detached, PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0)))
                run.cursor.instances[id] = GatewayHandle(nextSynthetic--)
                val ticks = d("ticks").toInt()
                val color = p["color"]?.let { s("color") }
                val handles = victims.map {
                    visual.spawnScreenVeil(castRef(run), it, s("sprite"), d("distance"), d("scale"), color, d("max_screen_fraction"), ticks)
                }
                run.veils[id] = VeilRec(now, ticks, handles)
            }
            "hurt" -> {
                val at = liveVec(run, s("from").removePrefix("instance:"), ctx)
                victims.forEach { gameplay.hurt(castRef(run), it, Vec3(at.x, at.y, at.z)) }
            }
            else -> fault(run, now, src, "effect '$kind' has no handler in TablePlayer")
        }
    }

    // ------------------------------------------------------------------ caster / frame drivers (R9, R40)

    private fun bool(run: Run, v: Val<Any>, ctx: RowCtx): Boolean = any(run, v, ctx) as Boolean

    private fun dashStart(run: Run, row: Row, p: Map<String, Val<Any>>, ctx: RowCtx, now: Int, src: String) {
        fun cell(k: String): Val<Any> = p[k] ?: throw IllegalArgumentException("effect 'dash' needs '$k'")
        val dir = any(run, cell("direction"), ctx) as Vector3d
        val params = DashDriver.Params(
            speed = dbl(run, cell("speed"), ctx),
            ticks = dbl(run, cell("ticks"), ctx).toInt(),
            directionLocal = Vec3(dir.x, dir.y, dir.z),
            ease = strOf(run, cell("ease"), ctx),
            followPitch = bool(run, cell("follow_pitch"), ctx),
            stopOnBlock = bool(run, cell("stop_on_block"), ctx),
            invulnerable = bool(run, cell("invulnerable"), ctx),
            faceMovement = bool(run, cell("face_movement"), ctx),
            passThrough = p["pass_through"]?.let { bool(run, it, ctx) } ?: false,
        )
        run.dash.start(row.instance.text, params)?.let { fault(run, now, src, it) }
        run.dash.drainFaults().forEach { fault(run, now, src, it) }
    }

    private fun reelStart(run: Run, row: Row, p: Map<String, Val<Any>>, ctx: RowCtx, now: Int, src: String) {
        fun cell(k: String): Val<Any> = p[k] ?: throw IllegalArgumentException("effect 'reel' needs '$k'")
        val frame = any(run, cell("frame"), ctx) as InstanceId
        val to = any(run, cell("to"), ctx) as InstanceId
        // a body whose flight ended on a hit stays a frozen frame (frames_out); the reel pulls it back, so it is live again from here
        val rec = run.recs[frame] ?: run.finished.remove(frame)?.also { run.recs[frame] = it }
        if (rec == null) {
            fault(run, now, src, "reel: frame '${frame.text}' is not a live instance")
            return
        }
        if (run.recs[to] == null) {
            fault(run, now, src, "reel: target '${to.text}' is not a live instance")
            return
        }
        val err = run.reel.start(row.instance.text, dbl(run, cell("speed"), ctx), strOf(run, cell("ease"), ctx))
        if (err != null) {
            fault(run, now, src, err)
            return
        }
        rec.flight = null
        run.reels[row.instance] = ReelRec(frame, to)
    }

    /**
     * `rig`: the swing drives the frame `of` (absent = `parent`), whose local pose in
     * the parent's axes is the grip origin and the arm-carried mount orientation each tick. A clip (`anim`) plays on the caster's own body
     * for the same span and carries the arm; `freezes` stop the rig clock when `respect_hit_stop` (the body's when `body_respect_hit_stop`).
     */
    private fun rigStart(run: Run, row: Row, p: Map<String, Val<Any>>, ctx: RowCtx, now: Int, src: String) {
        fun cell(k: String): Val<Any> = p[k] ?: throw IllegalArgumentException("effect 'rig' needs '$k'")
        val parent = any(run, cell("parent"), ctx) as InstanceId
        val of = p["of"]?.let { any(run, it, ctx) as InstanceId }
        if (of != null && run.recs[of] == null) {
            fault(run, now, src, "rig: frame '${of.text}' is not a live instance")
            return
        }
        val duration = (any(run, cell("duration"), ctx) as Number).toInt()
        val anim = p["anim"]?.let { any(run, it, ctx) as String }
        val clip = anim?.let { name ->
            val c = gameplay.bodyClip(name)
            if (c == null) {
                fault(run, now, src, "rig: no skin-rig clip '$name'")
                return
            }
            if (c.lengthTicks != duration) {
                fault(run, now, src, "rig: clip '$name' is ${c.lengthTicks} ticks but the rig lasts $duration: a performance body must cover exactly its duration")
                return
            }
            c
        }
        val caster = run.request.caster
        val slim = clip != null && gameplay.isSlimSkin(caster)
        val off = p["hand"]?.let { any(run, it, ctx) as String } == "off"
        val track = clip?.let { RigBodyTrack(it, slim, off) }
        @Suppress("UNCHECKED_CAST")
        val swing = (any(run, cell("swing"), ctx) as List<Map<String, Any>>).map { m ->
            RigPoseKey((m["t"] as Number).toInt(), (m["rotate"] as? Vector3d)?.let { Vec3(it.x, it.y, it.z) }, m["ease"]?.toString() ?: "linear")
        }
        val freezes = (any(run, cell("freezes"), ctx) as List<*>).map { f ->
            val m = f as Map<*, *>
            RigFreezeWindow((m["at"] as Number).toInt(), (m["ticks"] as Number).toInt())
        }
        fun v3(k: String): Vec3 = (any(run, cell(k), ctx) as Vector3d).let { Vec3(it.x, it.y, it.z) }
        val mounted = p["mount"] != null
        val respect = bool(run, cell("respect_hit_stop"), ctx)
        val bodyRespect = bool(run, cell("body_respect_hit_stop"), ctx)
        fun motion(hitStop: Boolean) = RigMotion(swing, v3("tilt"), v3("rest"), v3("pivot"), mounted, freezes, hitStop, track, slim, off)
        val pose = motion(respect)
        val bodyClock = if (bodyRespect && !respect) motion(true) else pose
        if (anim != null) gameplay.playBodyClip(castRef(run), caster, anim, 1.0, bodyClock.holds)
        // the rig is an anchor instance (ADR-0303 10.5): its rec lives at the rig's own id, carries the part's model points
        // (`rig.tip`, `rig.root`, ...) and holds the driver; with `of` the rec is only the carrier and the driven frame owns the pose.
        val carrier = row.instance
        if (run.recs[carrier] != null) endInstance(run, carrier)
        val identity = PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0))
        val parentRec = if (of == null) run.recs[parent] else null
        val carrierRec = Rec(SpawnKind.FRAME, parentRec?.let { parent }, if (of != null || parentRec != null) "" else parent.text, now, ctx.owner, ctx.detached, identity)
        run.recs[carrier] = carrierRec
        // points first: the carrier is already addressable, so a refused visual spawn below must not leave point(rig, ..) unresolvable for every later row
        for ((key, v) in p) if (key.startsWith("point.")) carrierRec.points[key.removePrefix("point.")] = (any(run, v, ctx) as Vector3d).let { if (of == null) Vector3d(it.x * SkinRigTransforms.PLAYER_SCALE, it.y * SkinRigTransforms.PLAYER_SCALE, it.z * SkinRigTransforms.PLAYER_SCALE) else it }
        var held: Rec? = null
        if (of == null) {
            // no `of`: the rig frame is carried by the arm: its origin is the grip and its rotation arm * tilt * pose * rest, and the held mesh
            // sits on it unrotated, so the drawn part (its `long` axis, display thirdperson_righthand) and the points turn together.
            run.cursor.instances[carrier] = GatewayHandle(nextSynthetic--)
            val heldId = InstanceId(carrier.path + InstanceId.Segment(OpId("held"), null))
            val local = PoseV(Vector3d(), Quaterniond(), Vector3d(SkinRigTransforms.PLAYER_SCALE))
            val rec = Rec(SpawnKind.ITEM, carrier, "", now, ctx.owner, ctx.detached, local)
            val view = DisplayView()
            // main hand: the caster's real stack (THIRDPERSON_RIGHTHAND); off hand: the off-hand part's own pack model (THIRDPERSON_LEFTHAND), the real off-hand slot is empty
            val h = if (off) {
                visual.spawnMesh(
                    castRef(run), any(run, cell("mesh_model"), ctx) as String, null, visualPose(rec, worldOf(run, rec, local, ctx.binding, now)), null,
                    MeshLook(mode = DisplayItemMode.THIRDPERSON_LEFTHAND, rigShading = true), view = view,
                )
            } else {
                visual.spawnMesh(
                    castRef(run), MeshModels.MAIN_HAND, null, visualPose(rec, worldOf(run, rec, local, ctx.binding, now)), null, MeshLook(rigShading = true), view = view,
                )
            }
            rec.real = h
            run.recs[heldId] = rec
            run.cursor.instances[heldId] = GatewayHandle(h.id)
            held = rec
        }
        val rig = RigRec(of ?: carrier, now, pose, bodyClock, anim, caster, held)
        carrierRec.rig = rig
        rigPose(run, rig, now)
    }

    private fun rigPose(run: Run, rig: RigRec, now: Int) {
        val rec = run.recs[rig.driven] ?: return
        val elapsed = now - rig.start
        val s = rig.motion.clock(elapsed)
        val bodyS = rig.bodyClock.clock(elapsed)
        val origin = rig.motion.originAt(bodyS)
        val q = rig.motion.frameRotationAt(s, bodyS)
        val cur = rec.localAt(now)
        val target = PoseV(Vector3d(origin.x, origin.y, origin.z), Quaterniond(q.x, q.y, q.z, q.w), Vector3d(cur.scale))
        rec.moveTo(target, now, 0)
        rec.real?.let { visual.write(it, visualPose(rec, worldOf(run, rec, target, null, now)), 0) }
        rig.held?.let { h -> h.real?.let { visual.write(it, visualPose(h, worldOf(run, h, h.localAt(now), null, now)), 0) } }
    }

    private fun driversStep(run: Run, now: Int) {
        run.dash.tick()
        run.dash.drainFaults().forEach { fault(run, now, "dash", it) }
        for ((id, line) in run.lines.entries.toList()) lineStep(run, id, line, now)
        for (veil in run.veils.values) {
            val age = (now - veil.start).coerceAtMost(veil.ticks)
            veil.handles.forEach { visual.updateScreenVeil(it, age) }
        }
        for ((id, reel) in run.reels.entries.toList()) reelStep(run, id, reel, now)
        for (rec in run.recs.values.toList()) rec.rig?.let { rigPose(run, it, now) }
    }

    /** One tick of a line ribbon: both ends are read again and the polyline is re-drawn between them (the one function W2 owns; the drain rule never touches it). */
    private fun lineStep(run: Run, id: InstanceId, line: LineRec, now: Int) {
        val h = run.recs[id]?.real ?: return
        // an end that cannot be read now (its entity left the world) keeps the line where it was: named, never dropped silently
        val vertices = try {
            lineVertices(run, line, RowCtx(line.base, line.binding, line.owner, line.level, line.scope), now)
        } catch (e: IllegalArgumentException) {
            fault(run, now, id.text, "ribbon line '${id.text}': end '${line.fromCell}' / '${line.toCell}' unreadable (${e.message}); the line stays at its last position")
            return
        }
        visual.updateLine(h, vertices)
    }

    /** The vertices of [line] at [now]: the two live ends, jagged by [LinePath] when the line has segments (epoch = ticks since spawn / every). */
    private fun lineVertices(run: Run, line: LineRec, ctx: RowCtx, now: Int): List<Vec3> {
        val (from, to) = lineEnds(run, line, ctx)
        val j = line.jitter ?: return listOf(from, to)
        val epoch = j.every?.let { (now - line.spawnTick) / it } ?: 0
        return LinePath.vertices(from, to, line.segments, j.spread, j.along, j.seed, epoch)
    }

    /** The cast-level seed of the line row `jitter.follow` names: found in the same row list (load already proved it is there). */
    private fun followSeed(run: Run, row: Row, followId: String): Long {
        val table = run.cursor.skill.table
        val lists = table.states.map { it.rows } + table.subTables.values.map { it.rows }
        val rows = lists.firstOrNull { l -> l.any { it === row } || l.any { it.instance == row.instance && it.tick == row.tick } } ?: emptyList()
        val target = rows.filter { (it.action as? Action.Spawn)?.kind == SpawnKind.RIBBON && LinePath.isFollowTarget(row.instance, followId, it) }
            .let { c -> c.firstOrNull { it.tick == row.tick } ?: c.firstOrNull() }
            ?: throw IllegalArgumentException("jitter.follow '$followId' has no line ribbon in this row list")
        return V3Bindings.rowSeed(run.request.seed, target.seed)
    }

    private fun lineEnds(run: Run, line: LineRec, ctx: RowCtx): Pair<Vec3, Vec3> {
        val f = liveVec(run, line.fromCell, ctx)
        val t = liveVec(run, line.toCell, ctx)
        return Vec3(f.x, f.y, f.z) to Vec3(t.x, t.y, t.z)
    }

    private fun reelStep(run: Run, id: InstanceId, reel: ReelRec, now: Int) {
        val frame = run.recs[reel.frame]
        val to = run.recs[reel.to]
        if (frame != null && isFrozen(run, reel.frame, now)) return
        if (frame == null || to == null) {
            run.reel.stop(id.text)
            run.reels.remove(id)
            fault(run, now, id.text, "reel: frame '${reel.frame.text}' or target '${reel.to.text}' ended while reeling")
            return
        }
        val here = worldOf(run, frame, frame.localAt(now), null, now).pos
        val target = worldOf(run, to, to.localAt(now), null, now).pos
        val st = run.reel.step(id.text, Vec3(here.x, here.y, here.z), Vec3(target.x, target.y, target.z)) ?: return
        val base = worldOf(run, frame, PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0)), null, now)
        val local = Vector3d(st.position.x, st.position.y, st.position.z).sub(base.pos).rotate(Quaterniond(base.rot).invert())
        val cur = frame.localAt(now)
        val next = PoseV(local, cur.rot, cur.scale)
        frame.moveTo(next, now, 0)
        frame.real?.let { visual.write(it, visualPose(frame, worldOf(run, frame, next, null, now)), 0) }
        if (st.arrived) {
            run.reels.remove(id)
            trace.event(run.cursor.handle, EventPath(id.text + ".end"), now, null)
            emit(run, EventPath(id.text + ".end"), now, null)
        }
    }

    private fun rigStop(run: Run, rig: RigRec) {
        rig.anim?.let { gameplay.stopBodyClip(castRef(run), rig.caster, it) }
    }

    private fun hasDriver(run: Run, id: InstanceId): Boolean =
        id in run.reels || run.recs[id]?.rig != null || run.dash.isActive(id.text) || run.reel.isActive(id.text)

    private fun endDrivers(run: Run, id: InstanceId) {
        run.dash.end(id.text)
        run.reel.stop(id.text)
        run.reels.remove(id)
        run.recs[id]?.let { rec -> rec.rig?.let { rigStop(run, it) }; rec.rig = null }
    }

    private fun stopDrivers(run: Run, keepDetached: Boolean = false) {
        run.dash.cancelAll()
        run.reel.stopAll()
        run.reels.clear()
        for (rec in run.recs.values) if (!(keepDetached && rec.detached)) rec.rig?.let { rigStop(run, it); rec.rig = null }
    }

    // ------------------------------------------------------------------ Val.Live resolution (C4c)

    private fun vec(run: Run, v: Vec, ctx: RowCtx): Vector3d = when (v) {
        is Val.Known -> Vector3d(v.value)
        is Val.Live -> liveVec(run, v.name, ctx)
    }

    private fun quat(run: Run, v: Rot, ctx: RowCtx, anchor: InstanceId? = null, anchorName: String = ""): Quaterniond = when (v) {
        is Val.Known -> Quaterniond(v.value)
        is Val.Live -> if (v.name.startsWith("align:")) alignRot(run, v.name, ctx, anchor, anchorName) else liveRot(run, v.name, ctx)
    }

    /**
     * `align:<axis>><to>^<up>` (R42): the LOCAL rotation that points the object's [axis] along the
     * named direction `to` with its up toward `up`, i.e. `Basis.facing(dir, up)` in world, expressed in the parent's space
     * (`parentWorld.conjugate * world`). Directions are `world.up`, `world.down` and `<instance|caster>.<axis>` ;
     * a bare anchor id is rejected at load , so every `to` here carries a dot.
     */
    private fun alignRot(run: Run, cell: String, ctx: RowCtx, anchor: InstanceId?, anchorName: String): Quaterniond {
        val body = cell.removePrefix("align:")
        val axisName = body.substringBefore('>')
        val to = body.substringAfter('>').substringBefore('^')
        val up = body.substringAfter('^')
        val parent = anchor?.let { run.recs[it] ?: run.finished[it] }?.let { worldOf(run, it, it.localAt(lastTick), ctx.binding, lastTick) }
            ?: if (anchorName.isNotEmpty() && anchorName != "world") PoseV(namedVec(run, anchorName, ctx.binding, ctx.scope), namedRot(run, anchorName, ctx.binding, ctx.scope), Vector3d(1.0, 1.0, 1.0)) else PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0))
        val dir = namedDirection(run, to, ctx)
        val upDir = namedDirection(run, up, ctx)
        val objAxis = axisName.split(',').map { it.toDouble() }.let { Vec3(it[0], it[1], it[2]) }
        val w = Basis.facing(dir, upDir, parentForward(parent.rot)) * axisToZ(objAxis)
        return Quaterniond(parent.rot).conjugate().mul(Quaterniond(w.x, w.y, w.z, w.w)).normalize()
    }

    private fun namedDirection(run: Run, name: String, ctx: RowCtx): Vec3 {
        if (name == "world.up") return Vec3(0.0, 1.0, 0.0)
        if (name == "world.down") return Vec3(0.0, -1.0, 0.0)
        val dot = name.lastIndexOf('.')
        if (dot <= 0) throw IllegalArgumentException("align target '$name' is not a direction (world.up, world.down, <frame>.<axis>)")
        val inst = name.substring(0, dot)
        val axis = name.substring(dot + 1)
        if (axis == "edge") {
            val (rec, _) = anchorOf(run, name, ctx.owner) ?: throw IllegalArgumentException("align target '$name': frame '$inst' has no edge point")
            val d = rec.points.getValue("edge")
            val r = worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick).rot
            return Vector3d(d).rotate(r).let { Vec3(it.x, it.y, it.z) }
        }
        val rot = if (inst == "caster") live.rot(run.cursor.handle, "caster", ctx.binding, ctx.scope)
        else anchorOf(run, inst, ctx.owner)?.let { (rec, _) -> worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick).rot }
            ?: throw IllegalArgumentException("align target '$name': no frame '$inst'")
        return Basis.axis(Frame(Vec3(0.0, 0.0, 0.0), Quaternion(rot.x, rot.y, rot.z, rot.w)), axis)
            ?: throw IllegalArgumentException("align target '$name': '$axis' is not a basis axis")
    }

    private fun num(run: Run, v: Num, ctx: RowCtx): Double = when (v) {
        is Val.Known -> v.value
        is Val.Live -> liveNum(run, v.name, ctx)
    }

    private fun int(run: Run, v: Val<Int>, ctx: RowCtx): Int = when (v) {
        is Val.Known -> v.value
        is Val.Live -> liveNum(run, v.name, ctx).toInt()
    }

    private fun any(run: Run, v: Val<Any>, ctx: RowCtx): Any = when (v) {
        is Val.Known -> v.value
        is Val.Live -> paramOf(run, v.name) ?: liveVec(run, v.name, ctx)
    }

    // The cells that need the player's own per-cast state are answered here; everything else goes to [LiveValueSource].

    /** `${p}` / `param:p`: the value the table was compiled with (an unknown name is a loud fault, never a zero). */
    private fun paramOf(run: Run, name: String): Any? {
        val key = when {
            name.startsWith("\${") && name.endsWith("}") -> name.substring(2, name.length - 1)
            name.startsWith("param:") -> name.removePrefix("param:")
            else -> return null
        }
        return run.cursor.skill.table.params[key] ?: throw IllegalArgumentException("param '$key' is not in the table of ${run.cursor.skill.table.skillId}")
    }

    /** `anchor:<instance>[.position|.rotation]` (or the bare instance text): the live instance and the requested part. */
    private val FRAME_PARTS = listOf("position", "rotation", "forward", "tip", "root", "edge")
    private val PLAIN_PARTS = setOf("position", "rotation", "forward", "")

    private fun anchorOf(run: Run, name: String, owner: StatePath? = null): Pair<Rec, String>? {
        // `/windup/volley` is the author's absolute spelling of the state-qualified instance `windup/volley`
        val bare = name.removePrefix("anchor:").removePrefix("/")
        // any model point of the frame's item part (`blade.tip`, `fly_body.pivot`, `weapon.grip`) is a part name as well
        val named = bare.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it !in FRAME_PARTS }
        for (part in FRAME_PARTS + listOfNotNull(named) + "") {
            val base = if (part.isEmpty()) bare else if (bare.endsWith(".$part")) bare.removeSuffix(".$part") else continue
            // a finished body / frame stays readable at its last pose (frames_out, ADR-0303 9): live recs first, then the frozen ones
            val id = instanceNamed(run.recs.keys, base, owner) ?: instanceNamed(run.finished.keys, base, owner) ?: continue
            val rec = run.recs[id] ?: run.finished.getValue(id)
            freshBody(rec)
            if (part !in PLAIN_PARTS && part !in rec.points) continue
            return rec to part
        }
        return null
    }

    /**
     * The instance a cell names: the full text (`fire/bolt`), or the author's local spelling (`bolt`, `blade[0]`) when exactly one live
     * instance ends that way; several are told apart by the row's own state [owner], else the cell is ambiguous and fails loudly.
     */
    private fun instanceNamed(pool: Collection<InstanceId>, base: String, owner: StatePath?): InstanceId? {
        pool.firstOrNull { it.text == base }?.let { return it }
        val local = pool.filter { it.text.substringAfterLast('/') == base }
        if (local.size <= 1) return local.firstOrNull()
        val mine = owner?.let { o -> local.filter { it.text.startsWith(o.value + "/") } }.orEmpty()
        return mine.singleOrNull() ?: throw IllegalArgumentException("'$base' names ${local.size} live instances (${local.joinToString { it.text }}); write the state-qualified id")
    }

    private fun liveVec(run: Run, name: String, ctx: RowCtx): Vector3d {
        paramOf(run, name)?.let { return toVector(it, name) }
        anchorOf(run, name, ctx.owner)?.let { (rec, part) ->
            if (part == "rotation") throw IllegalArgumentException("'$name' is a rotation, not a vector")
            val w = worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick)
            if (part !in PLAIN_PARTS && part != "edge") return Vector3d(rec.points.getValue(part)).rotate(w.rot).add(w.pos)
            // `edge` is a direction with a zero local origin : as a point it is the frame origin
            return if (part == "forward") Vector3d(0.0, 0.0, 1.0).rotate(w.rot) else w.pos
        }
        return namedVec(run, name, ctx.binding, ctx.scope)
    }

    /**
     * A live name the table player cannot answer from its own recs: a document `anchors:` entry (ADR-0303 1) is answered here from its
     * parent point and offset, so every host serves the same set; anything else is the host's own name ([live]).
     */
    private fun namedVec(run: Run, name: String, binding: EventBinding?, scope: RowScope): Vector3d {
        // chain targeting (ADR-0303 9.4): the `from` frame is the previous victim, answered by the host as `victim` of a binding that carries it
        if (name == "from" || name.startsWith("from.")) {
            return namedVec(run, "victim" + name.removePrefix("from"), binding?.copy(victim = binding.from), scope)
        }
        val doc = documentOf(run, name) ?: return live.vec(run.cursor.handle, name, binding, scope)
        if (doc.second == "rotation") throw IllegalArgumentException("'$name' is a rotation, not a vector")
        val pose = documentPose(run, doc.first, binding, scope)
        return when (doc.second) {
            "left" -> Vector3d(1.0, 0.0, 0.0).rotate(pose.rot)
            "up" -> Vector3d(0.0, 1.0, 0.0).rotate(pose.rot)
            "forward" -> Vector3d(0.0, 0.0, 1.0).rotate(pose.rot)
            else -> Vector3d(pose.pos)
        }
    }

    private fun namedRot(run: Run, name: String, binding: EventBinding?, scope: RowScope): Quaterniond {
        if (name == "from" || name.startsWith("from.")) {
            return namedRot(run, "victim" + name.removePrefix("from"), binding?.copy(victim = binding.from), scope)
        }
        val doc = documentOf(run, name) ?: return live.rot(run.cursor.handle, name, binding, scope)
        if (doc.second != "" && doc.second != "rotation") throw IllegalArgumentException("'$name' is a ${doc.second}, not a rotation")
        return Quaterniond(documentPose(run, doc.first, binding, scope).rot)
    }

    private fun documentOf(run: Run, name: String): Pair<AnchorDecl, String>? {
        val bare = name.removePrefix("anchor:")
        val part = listOf("position", "rotation", "left", "up", "forward").firstOrNull { bare.endsWith(".$it") } ?: ""
        val id = if (part.isEmpty()) bare else bare.removeSuffix(".$part")
        return run.cursor.skill.def.anchors.firstOrNull { it.id == id }?.let { it to part }
    }

    /** The parent point's pose displaced by the declared `offset` along the parent's axes (left +X, up +Y, forward +Z); `snapshot` is sampled once, `follow` on every read. */
    private fun documentPose(run: Run, decl: AnchorDecl, binding: EventBinding?, scope: RowScope): PoseV {
        run.snapshots[decl.id]?.let { return it }
        val parent = decl.anchor.toString()
        val pos: Vector3d
        val rot: Quaterniond
        val onInstance = anchorOf(run, parent)
        if (onInstance != null) {
            // a point of a live instance, e.g. a hand of the skill's own body clip (`body_anim.hand_r`)
            val (rec, part) = onInstance
            val w = worldOf(run, rec, rec.localAt(lastTick), binding, lastTick)
            pos = if (part in PLAIN_PARTS || part == "edge") Vector3d(w.pos) else Vector3d(rec.points.getValue(part)).rotate(w.rot).add(w.pos)
            rot = Quaterniond(w.rot)
        } else {
            pos = Vector3d(namedVec(run, parent, binding, scope))
            rot = Quaterniond(namedRot(run, parent, binding, scope))
        }
        for ((axis, v) in decl.offset) {
            val local = when (axis) {
                "left" -> Vector3d(1.0, 0.0, 0.0)
                "up" -> Vector3d(0.0, 1.0, 0.0)
                "forward" -> Vector3d(0.0, 0.0, 1.0)
                else -> throw IllegalArgumentException("document anchor '${decl.id}' offset key '$axis' is not forward / up / left")
            }
            val amount = when (v) {
                is Value.Literal -> v.value
                is Value.ParamRef -> (paramOf(run, "param:${v.name}") as? Number)?.toDouble() ?: throw IllegalArgumentException("document anchor '${decl.id}' offset '$axis': param '${v.name}' is not a number")
                is Value.Expr -> live.num(run.cursor.handle, "= ${v.source}", binding, scope)
            }
            pos.add(local.mul(amount).rotate(rot))
        }
        val pose = PoseV(pos, rot, Vector3d(1.0, 1.0, 1.0))
        if (decl.mode == AnchorMode.SNAPSHOT) run.snapshots[decl.id] = PoseV(Vector3d(pos), Quaterniond(rot), Vector3d(1.0, 1.0, 1.0))
        return pose
    }

    private fun liveRot(run: Run, name: String, ctx: RowCtx): Quaterniond {
        // the turn source of a projectile body is not carried by the cell: no extra turn
        if (name == "motion.turn") return Quaterniond()
        paramOf(run, name)?.let { v -> return (v as? Quaterniond)?.let { Quaterniond(it) } ?: throw IllegalArgumentException("param '$name' is not a rotation") }
        anchorOf(run, name, ctx.owner)?.let { (rec, part) ->
            // a point of a body clip (`body_anim.hand_r`) is a position in the body's frame: its rotation is that frame's
            if (part != "rotation" && part.isNotEmpty() && rec.body == null) throw IllegalArgumentException("'$name' is a ${part}, not a rotation")
            return worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick).rot
        }
        return namedRot(run, name, ctx.binding, ctx.scope)
    }

    private fun liveNum(run: Run, name: String, ctx: RowCtx): Double {
        paramOf(run, name)?.let { v ->
            return when (v) {
                is Number -> v.toDouble()
                is Boolean -> if (v) 1.0 else 0.0
                else -> throw IllegalArgumentException("param '$name' is not a number")
            }
        }
        return live.num(run.cursor.handle, name, ctx.binding, ctx.scope)
    }

    private fun toVector(v: Any, name: String): Vector3d = when (v) {
        is Vector3d -> Vector3d(v)
        is Vec3 -> Vector3d(v.x, v.y, v.z)
        is Number -> Vector3d(v.toDouble(), 0.0, 0.0)
        is List<*> -> if (v.size == 3 && v.all { it is Number }) Vector3d((v[0] as Number).toDouble(), (v[1] as Number).toDouble(), (v[2] as Number).toDouble())
        else throw IllegalArgumentException("param '$name' is not a 3-vector")
        else -> throw IllegalArgumentException("param '$name' is not a vector")
    }

    private fun liveTargets(run: Run, rule: TargetRule, ctx: RowCtx): List<UUID> = when {
        rule is TargetRule.Entity && rule.ref == "caster" -> listOf(run.request.caster)
        // a `.hit` event of this cast carries no attacker: it is always the caster hitting
        rule is TargetRule.Entity && rule.ref == "event.attacker" -> listOf(ctx.binding?.attacker ?: run.request.caster)
        rule is TargetRule.Area -> areaTargets(run, rule, ctx)
        else -> live.targets(run.cursor.handle, rule, ctx.binding).entities.map { it.id }
    }

    /** `enemies` keeps non-allies, `allies` keeps allies, `all` keeps everyone (the caster is excluded by the query unless the rule includes it). */
    private fun targetKindOk(run: Run, kind: String, entity: UUID): Boolean = when (kind) {
        "enemies" -> !query.isAlly(castRef(run), run.request.caster, entity)
        "allies" -> entity == run.request.caster || query.isAlly(castRef(run), run.request.caster, entity)
        "all", "blocks_only" -> true
        else -> throw IllegalArgumentException("target kind '$kind' is not enemies, allies or all")
    }

    private fun areaTargets(run: Run, rule: TargetRule.Area, ctx: RowCtx): List<UUID> {
        val c = rule.around?.let { id -> run.recs[id]?.let { worldOf(run, it, it.localAt(lastTick), ctx.binding, lastTick).pos } }
            ?: liveVec(run, rule.aroundName, ctx)
        val center = Vec3(c.x, c.y, c.z)
        val found = query.nearby(castRef(run), center, num(run, rule.radius, ctx), if (rule.includeSelf) null else run.request.caster)
            .filter { targetKindOk(run, rule.kind, it.entityId) }
            .sortedWith(compareBy({ it.feet.distance(center) }, { it.entityId }))
        val limited = rule.maxCount?.let { found.take(it) } ?: found
        return limited.map { it.entityId }
    }

    /** A numeric cell: a known number, or a live `${p}` / `= expr` read as a NUMBER (never through the vector path, which answers a CEL number as a vector). */
    private fun dbl(run: Run, v: Val<Any>, ctx: RowCtx): Double = when (v) {
        is Val.Known -> (v.value as Number).toDouble()
        is Val.Live -> liveNum(run, v.name, ctx)
    }

    private fun strOf(run: Run, v: Val<Any>, ctx: RowCtx): String = any(run, v, ctx).toString()

    private fun req(run: Run, look: Look, key: String, ctx: RowCtx): String =
        strOf(run, look.fields[key] ?: throw IllegalArgumentException("look needs '$key'"), ctx)

    private fun flag(run: Run, look: Look, key: String, ctx: RowCtx): Boolean = look.fields[key]?.let { any(run, it, ctx) as? Boolean } ?: false

    private fun ruleTargets(run: Run, rule: TargetRule, ctx: RowCtx): List<UUID> = liveTargets(run, rule, ctx)

    /** A target cell: a known [TargetSet], a known area [TargetRule], or a live `caster` / `victim` name. */
    private fun targets(run: Run, v: Val<Any>, ctx: RowCtx): List<UUID> = when (v) {
        is Val.Known -> when (val t = v.value) {
            is TargetRule -> ruleTargets(run, t, ctx)
            is TargetSet -> t.entities.map { it.id }
            else -> throw IllegalArgumentException("target cell holds ${t::class.simpleName}")
        }
        is Val.Live ->
            if (v.name.startsWith("caster")) listOf(run.request.caster)
            // ADR-0303 3.8 / op table `damage`: `event.attacker` is the bound attacker, never the victim
            else if (v.name == "event.attacker") liveTargets(run, TargetRule.Entity("event.attacker"), ctx)
            else live.targets(run.cursor.handle, TargetRule.Entity("event.victim"), ctx.binding).entities.map { it.id }
    }

    /**
     * The seed a row guard draws with: (cast seed, row content seed, firing tick, event index, victim). Each firing of a row draws
     * independently (one fresh draw per application), and a replay of the cast draws the same values.
     */
    private fun guardSeed(run: Run, row: Row, ctx: RowCtx, now: Int): Long {
        var h = rowScope(run, row).seed
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = h xor (now.toLong() * 0x2545F4914F6CDD1DL)
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        h = h xor ((ctx.binding?.index ?: -1).toLong() * 0x1B873593L)
        h = h xor ((ctx.binding?.victim?.id?.hashCode() ?: 0).toLong() * -0x61c8864680b583ebL)
        return h xor (h ushr 31)
    }

    /**
     * The frame lookup an expression sees (ADR-0303 3.5): a frame / projectile op id (`volley`) or `<id>.<point>` (`piece_a.tip`) names the live instance
     * whose last path segment is that id; the one sharing the longest path prefix with the row's instance wins, then the latest spawned. Unknown = null / ZERO.
     * Velocity is blocks per tick between the previous and the current tick of the instance's own chain (the caster's motion under a caster-anchored chain is not counted).
     */
    private fun framesOf(run: Run, row: Row, binding: EventBinding?): FrameLookup = object : FrameLookup {
        private fun find(name: String): Rec? = pick(run.recs, name) ?: pick(run.finished, name)

        private fun pick(source: Map<InstanceId, Rec>, name: String): Rec? {
            var best: Rec? = null
            var bestScore = -1
            for ((id, rec) in source) {
                if (id.path.lastOrNull()?.op?.value?.substringAfterLast('/') != name) continue
                var score = 0
                val mine = id.text.split('/')
                val theirs = row.instance.text.split('/')
                while (score < mine.size - 1 && score < theirs.size && mine[score] == theirs[score]) score++
                if (score >= bestScore) { best = rec; bestScore = score }
            }
            return best
        }

        private fun at(rec: Rec, point: String?, local: PoseV, t: Int): PoseV? {
            freshBody(rec)
            val w = worldOf(run, rec, local, binding, t)
            if (point == null) return w
            val p = rec.points[point] ?: return null
            return PoseV(Vector3d(p.x * w.scale.x, p.y * w.scale.y, p.z * w.scale.z).rotate(w.rot).add(w.pos), w.rot, w.scale)
        }

        /** A document `anchors:` entry named as a frame (`pos(palm)`): its pose now. A model point of an anchor does not exist. */
        private fun documentFrame(name: String, point: String?): Frame? {
            val decl = run.cursor.skill.def.anchors.firstOrNull { it.id == name } ?: return null
            if (point != null) return null
            val w = documentPose(run, decl, binding, RowScope.cast(run.request.seed))
            return Frame(Vec3(w.pos.x, w.pos.y, w.pos.z), Quaternion(w.rot.x, w.rot.y, w.rot.z, w.rot.w))
        }

        override fun worldPose(frame: String, point: String?): Frame? {
            val rec = find(frame) ?: return documentFrame(frame, point)
            val w = at(rec, point, rec.localAt(lastTick), lastTick) ?: return null
            return Frame(Vec3(w.pos.x, w.pos.y, w.pos.z), Quaternion(w.rot.x, w.rot.y, w.rot.z, w.rot.w))
        }

        override fun velocity(frame: String, point: String?): Vec3 {
            val zero = Vec3(0.0, 0.0, 0.0)
            val rec = find(frame)
                ?: if (run.cursor.skill.def.anchors.any { it.id == frame }) throw IllegalArgumentException("speed / velocity of the document anchor '$frame' is not served (name a frame op on it)") else return zero
            val now = at(rec, point, rec.localAt(lastTick), lastTick) ?: return zero
            val before = at(rec, point, rec.prevLocal(lastTick), lastTick - 1) ?: return zero
            return Vec3(now.pos.x - before.pos.x, now.pos.y - before.pos.y, now.pos.z - before.pos.z)
        }
    }

    private fun rowScope(run: Run, row: Row, binding: EventBinding? = null): RowScope {
        // the seed is the row's content seed (RowSeeds), never an op id: renaming or reordering ops keeps every spread
        val indices = row.instance.path.mapNotNull { it.index }.asReversed()
        return RowScope(V3Bindings.rowSeed(run.request.seed, row.seed), run.request.seed, indices, framesOf(run, row, binding), run.frameNames)
    }

    private fun rowStream(run: Run, row: Row, ctx: RowCtx): SeededStream =
        run.cursor.seed.fork("${row.seed}|${ctx.base}|${row.tick}")

    private fun castRef(run: Run): CastRef = CastRef(run.cursor.handle.value, run.request.caster, run.request.world, run.request.skill.def.item, run.request.heldItem)

    private fun fault(run: Run, tick: Int, src: String, message: String) {
        faultLog += PlayerFault(run.cursor.handle, tick, src, message)
    }

    // ------------------------------------------------------------------ state

    /**
     * A projectile body's flight: [start] + [dir] * the integral of [speed] over the ticks since spawn (closed form). With [gravity] / [drag]
     * (each tick `v = (v - (0, gravity, 0)) * (1 - drag)`, then the body moves by `v`) the launch
     * part is damped by `(1 - drag)^k` and the gravity part is stepped tick by tick; both zero keeps the exact closed form.
     */
    private class Flight(
        val dir: Vector3d,
        val speed: ProjectileKinematics.Speed,
        val start: Vector3d,
        val gravity: Double,
        val drag: Double,
    ) {
        private val keep = 1.0 - drag
        private var k = 0
        private var prevPath = 0.0
        private var launchOffset = 0.0
        private var fallVel = 0.0
        private var fallOffset = 0.0

        /** The position [ticks] whole ticks after spawn; steps the state forward one tick at a time (called with non-decreasing ticks). */
        fun at(ticks: Int): Vector3d {
            if (gravity == 0.0 && drag == 0.0) return Vector3d(dir).mul(ProjectileKinematics.pathLength(speed, ticks.toDouble())).add(start)
            while (k < ticks) {
                k++
                val path = ProjectileKinematics.pathLength(speed, k.toDouble())
                launchOffset += (path - prevPath) * Math.pow(keep, k.toDouble())
                prevPath = path
                fallVel = (fallVel - gravity) * keep
                fallOffset += fallVel
            }
            return Vector3d(dir).mul(launchOffset).add(start).add(0.0, fallOffset, 0.0)
        }
    }

    private class PoseV(val pos: Vector3d, val rot: Quaterniond, val scale: Vector3d)

    private class ReelRec(val frame: InstanceId, val to: InstanceId)

    private class RigRec(val driven: InstanceId, val start: Int, val motion: RigMotion, val bodyClock: RigMotion, val anim: String?, val caster: UUID, val held: Rec?)

    private class ShapeFrame(val frame: String, val from: String, val to: String)

    /** E1 `hit.shape` on a body with a `part:`: the capsule runs between two of the body's own points. */
    private class ShapeSelf(val from: String, val to: String)

    private class LockRec(val owner: StatePath?, val move: LockMove, val pitch: Boolean, val yaw: Boolean, val untilAbs: Int)

    /** A live thing: its local pose moves along one interpolation segment (closed form, same as the client's). */
    private class Rec(
        val kind: SpawnKind,
        val anchor: InstanceId?,
        var anchorName: String,
        val spawnTick: Int,
        val owner: StatePath?,
        /** `detach: true` (rev4.12): owned by the cast, not by [owner]; a state exit or a cast-end sweep of a non-detached owner skips it. */
        val detached: Boolean,
        var to: PoseV,
    ) {
        var real: VisualHandle? = null
        /** The world pose of the parent instance this rider was last placed against (null before the first look): [followRiders] writes the rider when it changes. */
        var parentSeen: PoseV? = null
        /** A `rig` anchor instance: the driver that poses [RigRec.driven] each tick (this rec itself when the rig has no `of`). */
        var rig: RigRec? = null
        /** The hit that spawned this instance (null outside `on.hit`): a `victim` anchor/axes keeps resolving to that victim after the row is gone (end-of-cast freeze, later ticks). */
        var binding: EventBinding? = null
        var flight: Flight? = null
        /** R16: the tick the flight ends by range / `for` (-1 none), the body events that end it early, and whether one did. */
        var expireTick: Int = -1
        var endsOn: Set<String> = emptySet()
        var ended: Boolean = false
        /** hit.targets, the sphere radius, the victims still allowed (`hit.times`, 1 for `once`) and those already hit. */
        var hitTargets: String = ""
        var hitRadius: Double = 0.0
        var hitTimes: Int = 0
        val hitVictims: MutableSet<UUID> = HashSet()
        /** R43 `hit.shape`: capsule radius and the rider frames (op id, `from` / `to` point names); empty = the body sphere [hitRadius]. */
        var shapeRadius: Double = 0.0
        var shapeFrames: List<ShapeFrame> = emptyList()
        var shapeSelf: ShapeSelf? = null
        var blockDespawn: Boolean = false

        /** `block.mode bounce`: restitution / friction / counted-bounce budget and how many were used; [reflect] (`hit.reflect`) bounces off victims too; [fused] (`block.fused`) a stick that holds to the flight end. */
        var bounceMode: Boolean = false
        var restitution: Double = 0.0
        var friction: Double = 0.0
        var bounceTimes: Int = 0
        var bounces: Int = 0
        var reflect: Boolean = false
        var fused: Boolean = false

        /** Ticks of the current [flight]'s clock origin: a bounce rebases the flight, so its clock restarts at that tick. */
        var clockBase: Int = 0

        /** `hit.attach_to`: the victim anchor a hit mounts the body on (null = `attach: false`); `block.for` ticks stuck; [stuckUntil] the tick it comes off (-1 flying). */
        var attachTo: String? = null
        /** `hit.attach.for`: ticks the body rides the victim (its victim ends it early when it is gone). */
        var attachTicks: Int = 0
        var stickTicks: Int = 0
        var stuckUntil: Int = -1
        var billboard: DisplayBillboard = DisplayBillboard.FIXED
        /** `frame axes`: which rotation a root frame takes (null = its anchor's own). */
        var axes: String? = null

        /** Ticks this body spent frozen: its flight clock runs `frozenTicks` behind the wall clock. */
        var frozenTicks: Int = 0

        /** Rig frame points: `tip` / `root` local offsets, `edge` a direction at the frame origin. */
        val points: MutableMap<String, Vector3d> = HashMap()

        /** A `body` instance whose clip declares named points: [points] are re-sampled from the clip each tick ([freshBody]). */
        var body: BodyPoints? = null

        /** The local rotation an `align` blend window started from (null outside a window). */
        var alignFrom: Quaterniond? = null
        private var from: PoseV = to
        private var segStart: Int = spawnTick
        private var segDur: Int = 0

        fun moveTo(target: PoseV, now: Int, duration: Int) {
            from = localAt(now)
            to = target
            segStart = now
            segDur = duration
        }

        /**
         * The local pose the rows of tick [t] - 1 saw: rows run before a tick's motion, so a duration-0 move made during tick [t] - 1 is
         * not visible to them yet. `localAt(t) - prevLocal(t)` is the true one-tick displacement a row of tick [t] sweeps (`localAt(t - 1)`
         * would return the post-move pose for a per-tick `moveTo` and collapse the segment to a point).
         */
        fun prevLocal(t: Int): PoseV =
            if (segDur <= 0) (if (segStart >= t - 1) from else to) else if (t - 1 < segStart) from else localAt(t - 1)

        /**
         * The local pose at the end of tick [t]. A duration-0 move made during tick [t] + 1 (a projectile's per-tick `moveTo`) is not yet
         * visible, so a swept contact segment `poseAtEndOf(now - 1) -> localAt(now)` has the true one-tick length ([localAt] would already
         * return the post-move pose for [t] and collapse the segment to a point).
         */
        fun poseAtEndOf(t: Int): PoseV =
            if (segDur <= 0) (if (segStart > t) from else to) else localAt(t)

        fun localAt(t: Int): PoseV {
            if (segDur <= 0 || t >= segStart + segDur) return to
            if (t <= segStart) return from
            val f = (t - segStart).toDouble() / segDur
            return PoseV(
                Vector3d(from.pos).lerp(to.pos, f), Quaterniond(from.rot).slerp(to.rot, f), Vector3d(from.scale).lerp(to.scale, f),
            )
        }
    }

    /** The clip of a `body` op, the shared skeleton its named points come from, how it plays on this caster ([slim] arm, clip [speed]). */
    private class BodyPoints(val clip: SkinRigAnimation, val skeleton: BodySkeleton, val slim: Boolean, val speed: Double)

    /**
     * Re-samples the named points of a `body` instance at the current tick: the clip's pose at `(tick - spawn) * speed`, each point carried through
     * its bone chain (`SkinRigTransforms.point`) into the caster's yaw frame (+X left, +Y up, +Z forward). A frozen (finished) instance keeps its last points.
     */
    private fun freshBody(rec: Rec) {
        val b = rec.body ?: return
        val frame = SkinRigPlayback(b.clip, b.speed).frameAt(lastTick - rec.spawnTick)
        for ((name, point) in b.skeleton.points) {
            val v = SkinRigTransforms.point(frame, b.slim, point)
            rec.points[name] = Vector3d(v.x, v.y, v.z)
        }
    }

    /** A live `shape: line` ribbon: the cells of its two ends and the scope of the row that spawned it. */
    private class LineRec(
        val fromCell: String, val toCell: String, val scope: RowScope, val binding: EventBinding?, val owner: StatePath?, val level: LevelCursor?, val base: Int,
        val segments: Int = 1, val jitter: LineJitter? = null, val spawnTick: Int = 0,
    )

    /** Load-fixed polyline displacement of a line ribbon: [seed] is the row seed (or the followed line's), [every] the re-jag period in ticks (null = rolled once). */
    private class LineJitter(val spread: Double, val along: Double, val every: Int?, val seed: Long)

    private class VeilRec(val start: Int, val ticks: Int, val handles: List<VisualHandle>)

    private class Run(
        val cursor: CastCursor, val request: CastRequest, val states: Map<StatePath, StateTable>, val listened: Set<EventPath>,
        val dash: DashDriver,
    ) {
        val reel = ReelDriver()
        val reels = LinkedHashMap<InstanceId, ReelRec>()
        val recs = LinkedHashMap<InstanceId, Rec>()

        /** The names an expression of this skill may use for its own anchors and instances: the compiler's declaration, rebuilt from the compiled definition. */
        val frameNames: Set<String> by lazy {
            val def = cursor.skill.def
            def.anchors.map { it.id }.toSet() + def.framesIn.keys + com.astarworks.astera.core.application.skillengine.load.grammar.ExprFrames.ofMachine(def.machine)
        }

        /** Document anchors with `mode: snapshot`, sampled at their first read. */
        val snapshots = HashMap<String, PoseV>()

        /** Frames and bodies that ended, frozen at their last pose: what a caller reads through a part's `frames_out` after the child is gone. */
        val finished = HashMap<InstanceId, Rec>()
        val pending = ArrayList<RuntimeEvent>()

        /** Victims this cast's own damage killed: later `status` ops skip them. */
        val killed = HashSet<UUID>()
        val external = ConcurrentLinkedQueue<RuntimeEvent>()
        val firings = HashMap<EventPath, Int>()

        /** Every event delivered so far in this cast: what a row's `maskUntil` is checked against. */
        val seen = HashSet<EventPath>()
        val hitOnce = HashMap<InstanceId, MutableSet<UUID>>()

        /** `freeze` on a frame instance: the tick it lifts (exclusive), per instance. */
        val frozenUntil = HashMap<InstanceId, Int>()

        /** Ops (identity without the occurrence index) that have found a victim at least once / already raised `miss`. */
        val hitEver = HashSet<InstanceId>()
        val missFired = HashSet<InstanceId>()
        val pendingEnds = ArrayList<InstanceId>()
        val locks = ArrayList<LockRec>()

        /** R45: how many machine-root `world` rows have been played; and the body clip each live `body` instance plays. */
        var nextWorld = 0
        val bodyClips = HashMap<InstanceId, Pair<UUID, String>>()

        /** Live `held_model` instances (each holds one swap of the caster's weapon model). */
        val heldModels = HashSet<InstanceId>()

        /** Live `screen_veil` instances (their quads are re-placed every tick). */
        val veils = LinkedHashMap<InstanceId, VeilRec>()

        /** Live line ribbons (their ends are re-read every tick). */
        val lines = LinkedHashMap<InstanceId, LineRec>()

        /** Final regions seen per parallel parent (`<parent>.end` is published when all are final). */
        val regionEnds = HashMap<StatePath, Int>()

        /** Per-instance `set` overrides of `count` / `volume`, read by that instance's later particle / sound rows. */
        val fields = HashMap<InstanceId, HashMap<String, Double>>()

        /** `hit` re-hit window `every N`: tick of the last hit per (instance, victim). */
        val hitLast = HashMap<InstanceId, HashMap<UUID, Int>>()
        var inTick = false
        var finishing = false

        /** [releaseCast] ran for this finishing run (so the drain of handler rows starts from a cast that has ended). */
        var released = false

        /** A cancel cut the run: no tail (detached visuals end with it). */
        var cutting = false

        /** The tick the tail ends (the run finalizes after its rows); [Int.MIN_VALUE] until the cast has ended. */
        var tailEnd = Int.MIN_VALUE
        var finalized = false
    }
}
