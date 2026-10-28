package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.runtime.CastError
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.ops.DashDriver
import com.astarworks.astera.core.application.skillengine.runtime.ops.ReelDriver
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.world.WorldRows
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
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.application.skillengine.load.grammar.LocalAxes
import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.hit.DefaultContactResolver
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
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
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.application.skillengine.load.MeshModels
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.Num
import com.astarworks.astera.core.domain.skillengine.table.Rot
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.application.skillengine.compose.ProjectileKinematics
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.LockEnd
import com.astarworks.astera.core.domain.skillengine.table.HitWindow
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.Vec
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
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
        val rootPath = StatePath(skill.def.machine.start)
        val states = skill.table.states.associateBy { it.state }
        if (rootPath !in states) return Result.Failure(CastError.FrameSetup("start state '${rootPath.value}' has no table in ${skill.table.skillId}"))
        val handle = CastHandle(nextCast++)
        val root = LevelCursor(rootPath, request.startTick, 0)
        val cursor = CastCursor(handle, skill, SeededStream(request.seed), listOf(root), LinkedHashMap(), ArrayList())
        val listened = HashSet<EventPath>(skill.table.subTables.keys)
        skill.table.states.forEach { st -> st.branches.forEach { listened += it.event } }
        skill.table.subTables.values.forEach { sub -> sub.branches.forEach { listened += it.event } }
        runs[handle.value] = Run(cursor, request, states, listened, DashDriver(CastRef(handle.value, request.caster, request.world), movement, query))
        lastTick = max(lastTick, request.startTick)
        return Result.Success(handle)
    }

    override fun tick(now: Int) {
        lastTick = now
        world.tick()
        for (run in runs.values.toList()) {
            if (run.finalized || now < run.request.startTick) continue
            tickRun(run, now)
        }
        runs.values.removeAll { it.finalized }
    }

    override fun deliver(cast: CastHandle, event: RuntimeEvent) {
        runs[cast.value]?.external?.add(event)
    }

    override fun cancel(cast: CastHandle, scope: CancelScope) {
        val run = runs[cast.value] ?: return
        if (run.finalized) return
        val now = lastTick
        cancelScope(run, scope, now, null)
        settle(run, now)
        runs.values.removeAll { it.finalized }
    }

    override fun live(): List<CastHandle> = runs.values.filter { !it.finalized }.map { it.cursor.handle }

    // ------------------------------------------------------------------ one tick (10.2a)

    private fun tickRun(run: Run, now: Int) {
        if (now - run.request.startTick >= config.maxCastTicks) {
            fault(run, now, "cast", "cast older than max_cast_ticks (${config.maxCastTicks}); swept without exit actions")
            finalize(run, now)
            return
        }
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
        // 5. cleanup
        run.inTick = false
        cleanup(run, now)
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
        for (lvl in run.cursor.levels.toList()) {
            if (lvl !in run.cursor.levels || lvl.done || lvl.firedTick == now) continue
            val st = run.states[lvl.state] ?: continue
            val lt = now - lvl.enteredAt
            // `after:` and `for:` + `then:` are both `timer(n)` branches; declaration order, first match wins (item 14.8)
            val br = st.branches.firstOrNull { b ->
                b.event.value.startsWith("timer(") && lt in b.window.min..b.window.max &&
                    guardOk(run, b, EventBinding(b.event, null, null, null), lt, now)
            } ?: continue
            transition(run, lvl, br.target, br.subTable, ExitReason.TIMER, now, EventBinding(br.event, null, null, null))
        }
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
        if (run.finishing) finalize(run, now)
    }

    private fun finalize(run: Run, now: Int) {
        if (run.finalized) return
        for (id in run.recs.keys.toList().asReversed()) endInstance(run, id)
        stopDrivers(run)
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
            playRow(run, row, RowCtx(lvl.enteredAt, null, lvl.state, lvl), now)
        }
    }

    private fun advanceSub(run: Run, sc: SubCursor, now: Int) {
        while (sc.nextRow < sc.sub.rows.size && sc.sub.rows[sc.nextRow].tick + sc.firedAt <= now) {
            val row = sc.sub.rows[sc.nextRow++]
            playRow(run, row, RowCtx(sc.firedAt, sc.binding, sc.owner, null), now)
        }
    }

    private fun enterLevel(run: Run, state: StatePath, parent: LevelCursor?, now: Int, at: Int? = null): LevelCursor? {
        val st = run.states[state]
        if (st == null) {
            fault(run, now, state.value, "state has no table")
            return null
        }
        val lvl = LevelCursor(state, now, 0)
        lvl.parent = parent
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

    private fun exitLevel(run: Run, lvl: LevelCursor, reason: ExitReason, now: Int, binding: EventBinding?) {
        for (child in run.cursor.levels.filter { it.parent === lvl }.asReversed()) exitLevel(run, child, ExitReason.PREEMPT, now, binding)
        pasteSub(run, EventPath("${lvl.state.value}.exit[${reason.name.lowercase()}]"), now, binding, owner = null, direct = true)
        // End rows the table already played this tick are the contract and run in row order; the sweep below only closes what is still alive
        for (id in run.pendingEnds.toList()) endInstance(run, id)
        run.pendingEnds.clear()
        for ((id, rec) in run.recs.entries.toList().asReversed()) if (rec.owner == lvl.state) endInstance(run, id)
        run.regionEnds.remove(lvl.state)
        for (l in run.locks.filter { it.owner == lvl.state }) {
            run.locks.remove(l)
            hooks.unlock(run.request.caster, l.move, l.pitch, l.yaw)
        }
        run.cursor.levels = run.cursor.levels.filter { it !== lvl }
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
        enterLevel(run, target, parent, now, at)
    }

    // ------------------------------------------------------------------ events

    private fun deliverEvent(run: Run, ev: RuntimeEvent, now: Int) {
        if (!bodyEventLive(run, ev.path)) return
        run.seen += ev.path
        val binding = EventBinding(ev.path, ev.index, ev.contact, ev.victim, ev.attacker)
        val offered = offer(run, ev, binding, now, only = null)
        val sub = run.cursor.skill.table.subTables[ev.path] ?: return
        if (ev.path in offered.pasted) return
        val owner = run.cursor.levels.firstOrNull { it.state == sub.owner } ?: return
        val lt = now - owner.enteredAt
        if (lt < sub.window.min || lt > sub.window.max) return
        if ((run.firings[ev.path] ?: 0) >= sub.maxFirings) return
        run.firings[ev.path] = (run.firings[ev.path] ?: 0) + 1
        pasteSub(run, ev.path, now, binding, owner = sub.owner, direct = false)
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
                val binding = ctx?.binding
                // event `cancel` to the innermost level (an `on: cancel` handler), CANCEL reason up the chain, then End(root)
                val innermost = run.cursor.levels.maxByOrNull { it.depth }
                if (innermost != null) {
                    val ev = RuntimeEvent(EventPath("cancel"), now, null, null, null)
                    offer(run, ev, EventBinding(ev.path, null, null, null), now, only = innermost)
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
        val doomed = run.recs.keys.filter { k -> k.path.size >= prefix.size && k.path.subList(0, prefix.size) == prefix }
        for (k in doomed.asReversed()) {
            val rec = run.recs.remove(k) ?: continue
            run.cursor.instances.remove(k)
            run.bodyClips.remove(k)?.let { gameplay.stopBodyClip(castRef(run), run.request.caster, it) }
            rec.real?.let { visual.remove(it) }
        }
    }

    private fun worldOf(run: Run, rec: Rec, local: PoseV, binding: EventBinding?, t: Int, depth: Int = 0): PoseV {
        var pp = Vector3d()
        var pr = Quaterniond()
        val parent = rec.anchor?.let { run.recs[it] }
        if (parent != null && depth < 16) {
            val pw = worldOf(run, parent, parent.localAt(t), binding, t, depth + 1)
            pp = pw.pos
            pr = pw.rot
        } else if (rec.anchorName.isNotEmpty() && rec.anchorName != "world") {
            pp = live.vec(run.cursor.handle, rec.anchorName, binding, RowScope.cast(run.request.seed))
            pr = live.rot(run.cursor.handle, rec.anchorName, binding, RowScope.cast(run.request.seed))
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
    private class RowCtx(val base: Int, val binding: EventBinding?, val owner: StatePath?, val level: LevelCursor?, var scope: RowScope = RowScope.cast(0L))

    private fun playRow(run: Run, row: Row, ctx: RowCtx, now: Int) {
        // ADR-0303 2.0a: `until` is a mask over candidate rows; the named event cuts every remaining candidate
        if (row.maskUntil.isNotEmpty() && row.maskUntil.any { it in run.seen }) return
        val src = row.src.value.ifEmpty { row.instance.text }
        val g = row.guard
        if (g != null) {
            // a class-L / seeded `when` on a point gameplay op (ADR-0303 item 13): the row exists, the guard decides at its tick
            val ok = hooks.guard(run.cursor.handle, g, run.cursor.skill.table.conditions, ctx.binding, now - ctx.base, guardSeed(run, row, ctx, now))
            if (ok == null) { fault(run, now, src, "no guard evaluator for $g"); return }
            if (!ok) return
        }
        ctx.scope = rowScope(run, row)
        try {
            when (val a = row.action) {
                is Action.Enter -> {
                    trace.enter(run.cursor.handle, a.state, now)
                    val lvl = ctx.level
                    if (lvl != null && a.state != lvl.state) {
                        if (a.state.value.startsWith(lvl.state.value + "/")) enterLevel(run, a.state, lvl, now)
                        else fault(run, now, src, "Enter(${a.state.value}) is not a child of ${lvl.state.value}")
                    }
                }
                is Action.Exit -> trace.exit(run.cursor.handle, a.state, a.reason, now)
                is Action.Spawn -> spawn(run, row, a, ctx, now)
                is Action.Key -> key(run, a, ctx, now)
                is Action.Teleport -> teleport(run, a, ctx, now)
                is Action.TrailPoint -> trailPoint(run, a, ctx, now)
                is Action.Particle -> particle(run, row, a, ctx, now)
                is Action.Sound -> sound(run, row, a, ctx)
                is Action.Hit -> hit(run, row, a, ctx, now)
                is Action.Damage -> damage(run, row, a, ctx, now)
                is Action.ParticleDef -> particleDef(run, row, a, ctx)
                is Action.Set -> set(run, row, a, ctx, now, src)
                is Action.Status -> for (v in ruleTargets(run, a.targets, ctx)) {
                    gameplay.applyStatus(run.request.caster, v, a.status, int(run, a.stacks, ctx), a.forTicks)
                }
                is Action.Effect -> effect(run, row, a, ctx, now, src)
                is Action.World -> WorldRows.execute(world, castRef(run), a, vec(run, a.origin, ctx), vec(run, a.forward, ctx)) { cell -> num(run, cell as Num, ctx) }
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
                            run.pending += RuntimeEvent(EventPath(a.instance.text + ".expire"), now, idx, null, null)
                            body.expireTick = -1
                        }
                        run.pending += RuntimeEvent(EventPath(a.instance.text + ".end"), now, idx, null, null)
                    }
                    if (a.instance !in run.pendingEnds) run.pendingEnds += a.instance
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
        val rec = Rec(a.kind, if (body) null else a.anchor, if (body) "" else a.anchorName, now, ctx.owner, local)
        rec.billboard = a.look.fields["billboard"]?.let { runCatching { DisplayBillboard.valueOf(strOf(run, it, ctx).uppercase()) }.getOrNull() } ?: DisplayBillboard.FIXED
        rec.axes = a.look.fields["axes"]?.let { strOf(run, it, ctx) }
        run.recs[id] = rec
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
            rec.blockDespawn = mode == "despawn"
            val once = lf["hit.once"]?.let { any(run, it, ctx) } == true
            rec.hitTimes = if (once) 1 else lf["hit.times"]?.let { dbl(run, it, ctx).toInt() } ?: 1
            rec.endsOn = buildSet {
                if (lf["hit.once"]?.let { any(run, it, ctx) } == true) add("hit")
                if (mode == "despawn" || mode == "stick") add("block")
            }
        }
        val world = worldOf(run, rec, local, ctx.binding, now)
        val pose = visualPose(rec, world)
        val cast = castRef(run)
        val look = a.look
        val brightness = look.fields["brightness"]?.let { int(run, it as Val<Int>, ctx) }
        val glow = look.fields["glow"]?.let { strOf(run, it, ctx) }
        val h: VisualHandle? = when (a.kind) {
            SpawnKind.FRAME, SpawnKind.PROJECTILE_BODY -> null
            SpawnKind.ITEM -> visual.spawnMesh(
                cast, req(run, look, "item", ctx), look.fields["part"]?.let { strOf(run, it, ctx) }, pose, brightness,
                MeshLook(
                    itemModel = look.fields["model"]?.let { strOf(run, it, ctx) },
                    mode = look.fields["mode"]?.let { DisplayItemMode.valueOf(strOf(run, it, ctx).uppercase()) } ?: DisplayItemMode.FIXED,
                    rigShading = look.fields["rig_shading"]?.let { any(run, it, ctx) as? Boolean } ?: true,
                ),
                glow = glow,
            )
            SpawnKind.BLOCK -> visual.spawnBlock(cast, req(run, look, "block", ctx), pose, brightness, glow = glow)
            SpawnKind.TEXT -> visual.spawnText(
                cast, req(run, look, "text", ctx), look.fields["background"]?.let { strOf(run, it, ctx) } ?: "#00000000",
                flag(run, look, "shadow", ctx), flag(run, look, "see_through", ctx), pose, brightness,
            )
            SpawnKind.SPRITE -> visual.spawnPlate(
                cast, req(run, look, "sprite", ctx), look.fields["alpha"]?.let { dbl(run, it, ctx) } ?: 1.0,
                look.fields["color"]?.let { strOf(run, it, ctx) }, flag(run, look, "see_through", ctx), pose, brightness, glow = glow,
            )
            SpawnKind.RIBBON -> visual.spawnTrail(
                cast, dbl(run, look.fields["ticks"] ?: throw IllegalArgumentException("ribbon look needs 'ticks'"), ctx).toInt(),
                look.fields["width"]?.let { dbl(run, it, ctx) } ?: 1.0, look.fields["color"]?.let { strOf(run, it, ctx) } ?: "#ffffff",
                look.fields["alpha"]?.let { dbl(run, it, ctx) } ?: 1.0,
            ).also { th ->
                visual.setTrailLook(th, look.fields["fade_power"]?.let { dbl(run, it, ctx) } ?: 1.0, look.fields["style"]?.let { strOf(run, it, ctx) } ?: "soft")
            }
        }
        val gh = if (h != null) GatewayHandle(h.id) else GatewayHandle(nextSynthetic--)
        rec.real = h
        // a glyph quad (v1 VfxQuad.style): the text colour and opacity of the spawn row are written like a first `set` (spawnText has no slot for them)
        if (h != null && a.kind == SpawnKind.TEXT) {
            look.fields["color"]?.let { hooks.setDisplayText(h, "color", strOf(run, it, ctx)) }
            look.fields["text_opacity"]?.let { op -> dbl(run, op, ctx).takeIf { it < 255.0 }?.let { hooks.setDisplayField(h, "text_opacity", it / 255.0) } }
        }
        run.cursor.instances[id] = gh
    }

    private fun key(run: Run, a: Action.Key, ctx: RowCtx, now: Int) {
        val rec = run.recs[a.display] ?: return
        val target = PoseV(vec(run, a.translation, ctx), quat(run, a.leftRotation, ctx, rec.anchor, rec.anchorName), vec(run, a.scale, ctx))
        rec.moveTo(target, now, a.durationTicks)
        rec.real?.let { visual.write(it, visualPose(rec, worldOf(run, rec, target, ctx.binding, now)), a.durationTicks) }
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

    private fun particle(run: Run, row: Row, a: Action.Particle, ctx: RowCtx, now: Int) {
        val pos = vec(run, a.position, ctx)
        val off = vec(run, a.offset, ctx)
        val (hex, size) = particleData(run, row, a.data, now)
        val req = run.request
        particles.emit(
            castRef(run),
            ParticleBurst(
                particle = a.type, at = Vec3(pos.x, pos.y, pos.z), count = run.fields[row.instance]?.get("count")?.toInt() ?: int(run, a.count, ctx), offset = Vec3(off.x, off.y, off.z),
                speed = num(run, a.extra, ctx), colorHex = hex, dataSize = size, dir = null, spreadDeg = 0.0, inheritVelocity = null,
                look = Frame.fromYawPitch(Vec3.ZERO, req.casterYawDeg, req.casterPitchDeg).forward(),
                seed = rowStream(run, row, ctx).nextLong(),
            ),
        )
    }

    private fun particleData(run: Run, row: Row, d: ParticleData?, now: Int): Pair<String?, Double?> {
        fun hex(c: com.astarworks.astera.core.domain.effect.ParticleColor) = "#%02x%02x%02x".format(c.r, c.g, c.b)
        return when (d) {
            null -> null to null
            is ParticleData.Dust -> hex(d.color) to d.scale.toDouble()
            is ParticleData.DustTransition -> hex(d.from) to d.scale.toDouble()
            is ParticleData.Tint -> hex(d.color) to null
            is ParticleData.Spell -> hex(d.color) to d.power.toDouble()
            else -> {
                fault(run, now, row.src.value, "particle data ${d::class.simpleName} has no ParticleBurst field")
                null to null
            }
        }
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
                particle = a.def, at = Vec3(pos.x, pos.y, pos.z), count = run.fields[row.instance]?.get("count")?.toInt(), offset = Vec3.ZERO,
                speed = 0.0, colorHex = null, dataSize = null, dir = null, spreadDeg = 0.0, inheritVelocity = null,
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
        // v2 `resolveHits`: `if (isFrozen(...)) continue` (CastInstance.kt:757)
        a.anchor?.let { if (isFrozen(run, it, now)) return }
        val delta = a.anchor?.let { run.recs[it] }?.let { r ->
            val cur = worldOf(run, r, r.localAt(now), ctx.binding, now).pos
            val prev = worldOf(run, r, r.localAt(now - 1), ctx.binding, now - 1).pos
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
        val bindings = nearest.mapIndexed { idx, e ->
            val contact = ContactInfo(Vector3d(e.value.first.x, e.value.first.y, e.value.first.z), Vector3d(e.value.second.x, e.value.second.y, e.value.second.z))
            e.key to EventBinding(hitPath, idx, contact, EntityRef(e.key))
        }
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
            if (comps.isNotEmpty()) gameplay.damage(run.cursor.skill.def.id, caster, victim, comps, seen.getValue(victim).first, a.ignoreInvulnerability, false)
        }
        for ((_, b) in bindings) run.pending += RuntimeEvent(hitPath, now, b.index, b.contact, b.victim)
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
            // a frozen body (v2 `isFrozen`, CastInstance.kt:726-734) holds its place: its flight clock and range timer stand still, no contact is tested
            if (isFrozen(run, id, now)) {
                rec.frozenTicks++
                if (rec.expireTick >= 0) rec.expireTick++
                continue
            }
            rec.flight?.let { fl ->
                val cur = rec.localAt(now)
                rec.moveTo(PoseV(fl.at(now - rec.spawnTick - rec.frozenTicks), cur.rot, cur.scale), now, 0)
            }
            val blocked = blockContact(run, id, rec, now)
            if (!blocked) entityContact(run, id, rec, now)
            // R16: `expire` is the end by range / `for`, just before `end`; a hit / block end (rec.ended) never expires (checked at delivery)
            if (rec.expireTick == now) run.pending += RuntimeEvent(EventPath(id.text + ".expire"), now, id.path.lastOrNull()?.index, null, null)
        }
    }

    /** v2 `CastInstance.isFrozen` (:726-734): the instance or any frame it rides is inside its `freeze` window. */
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

    /** Fires `<body>.block` for a block on this tick's segment; true when that block ends the flight (despawn / stick), so no entity is tested behind it. */
    private fun blockContact(run: Run, id: InstanceId, rec: Rec, now: Int): Boolean {
        val path = EventPath(id.text + ".block")
        if (path !in run.listened) return false
        val cur = worldOf(run, rec, rec.localAt(now), null, now).pos
        val prev = worldOf(run, rec, rec.localAt(now - 1), null, now - 1).pos
        if (cur.distanceSquared(prev) < 1e-12) return false
        val b = query.raycastBlock(castRef(run), Vec3(prev.x, prev.y, prev.z), Vec3(cur.x, cur.y, cur.z)) ?: return false
        run.pending += RuntimeEvent(
            path, now, id.path.lastOrNull()?.index, ContactInfo(Vector3d(b.point.x, b.point.y, b.point.z), Vector3d(b.normal.x, b.normal.y, b.normal.z)), null,
        )
        if (rec.blockDespawn) endByContact(run, id, rec, now)
        return "block" in rec.endsOn
    }

    /** The flight ends at a contact (last victim, or a despawn block): `end` right after the contact event, the body goes at cleanup. */
    private fun endByContact(run: Run, id: InstanceId, rec: Rec, now: Int) {
        rec.ended = true
        run.pending += RuntimeEvent(EventPath(id.text + ".end"), now, id.path.lastOrNull()?.index, null, null)
        if (id !in run.pendingEnds) run.pendingEnds += id
    }

    /**
     * Fires `<body>.hit` (with `event.victim` and `contact`) for every victim the body's swept sphere reaches this tick, nearest the segment start first.
     * 1:1 with the v2 projectile contact (`9d4c4a3db:plugin/combat/.../ProjectileStepper.kt:117-124`, `stepFlight`): the segment `from -> to` of one tick,
     * inflated by the hit radius (`radiusOf`), the owner never a victim (`ProjectileWorldProbe` contract, :52), each victim hit once.
     * `hit.once` ends the flight at the first victim (`.end` follows at once); otherwise it ends after `hit.times` victims.
     */
    private fun entityContact(run: Run, id: InstanceId, rec: Rec, now: Int) {
        if (rec.hitTimes <= 0 || now == rec.spawnTick) return
        val path = EventPath(id.text + ".hit")
        if (path !in run.listened && path !in run.cursor.skill.table.subTables) return
        if (rec.hitTargets == "blocks_only") return
        val cur = worldOf(run, rec, rec.localAt(now), null, now).pos
        val prev = worldOf(run, rec, rec.localAt(now - 1), null, now - 1).pos
        val delta = Vec3(cur.x - prev.x, cur.y - prev.y, cur.z - prev.z)
        val start = Vec3(prev.x, prev.y, prev.z)
        val raw = ArrayList<ContactEvent>()
        fun test(vol: ResolvedVolume, velocity: Vec3) {
            val cands = query.nearby(castRef(run), vol.broadCenter, vol.broadRadius, run.request.caster)
                .filter { it.entityId !in rec.hitVictims && targetKindOk(run, rec.hitTargets, it.entityId) }
            raw += resolver.narrow(id.text, now, id.text, vol, velocity, cands)
        }
        if (rec.shapeFrames.isEmpty()) test(ResolvedVolume.Capsule(start, Vec3(cur.x, cur.y, cur.z), rec.hitRadius), delta)
        else for (sf in rec.shapeFrames) {
            // R43: one swept capsule per rider frame, swept back along the frame's own last-tick displacement (the same poses rule as the `hit` op, item 14.7)
            val fr = run.recs.entries.firstOrNull { (k, r) ->
                r.kind == SpawnKind.FRAME && k.path.size == id.path.size && k.path.dropLast(1) == id.path.dropLast(1) && k.path.last().op.value == sf.frame &&
                    k.path.last().index == id.path.last().index
            }?.value
            if (fr == null) {
                fault(run, now, id.text, "hit.shape frame '${sf.frame}' is not a live frame of this projectile")
                continue
            }
            val d = Vector3d(worldOf(run, fr, fr.localAt(now), null, now).pos).sub(worldOf(run, fr, fr.localAt(now - 1), null, now - 1).pos)
            val f = live.vec(run.cursor.handle, sf.from, null, RowScope.cast(run.request.seed))
            val t = live.vec(run.cursor.handle, sf.to, null, RowScope.cast(run.request.seed))
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
        val found = raw.distinctBy { it.victim }.sortedBy { it.pointWorld.distance(start) }
        for (ce in found) {
            if (rec.hitTimes <= 0) break
            rec.hitVictims += ce.victim
            rec.hitTimes--
            run.pending += RuntimeEvent(
                path, now, id.path.lastOrNull()?.index,
                ContactInfo(Vector3d(ce.pointWorld.x, ce.pointWorld.y, ce.pointWorld.z), Vector3d(ce.normalWorld.x, ce.normalWorld.y, ce.normalWorld.z)), EntityRef(ce.victim),
            )
        }
        if (rec.hitTimes <= 0 && found.isNotEmpty()) {
            endByContact(run, id, rec, now)
        }
    }

    private fun damage(run: Run, row: Row, a: Action.Damage, ctx: RowCtx, now: Int) {
        val comps = a.components.map { c -> SkillDamage(c.attribute.name.lowercase(), liveAmount(run, c, ctx)) }
        for (v in ruleTargets(run, a.targets, ctx)) {
            val point = ctx.binding?.contact?.point?.let { Vec3(it.x, it.y, it.z) } ?: query.rootOf(castRef(run), v)?.feet ?: Vec3.ZERO
            gameplay.damage(run.cursor.skill.def.id, run.request.caster, v, comps, point, false, false)
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
                // v2 MovementOpExecutors Knockback: impulse(direction * strength), the direction is not normalised
                val vv = v("direction")
                val k = d("strength")
                victims.forEach { gameplay.impulse(it, Vec3(vv.x * k, vv.y * k, vv.z * k)) }
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
                // v2 ConsumeItemOpExecutor (9d4c4a3db:kernel/runtime/.../runtime/ops/ControlOpExecutors.kt:171): a zero count is a no-op
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
                        // v2 `FreezeOpExecutor` split the scope into entities and frames (ControlOpExecutors.kt:14-33, `host.freezeFrames`);
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
                val from = if (dir == "from_hit") liveVec(run, "event.victim", ctx).let { Vec3(it.x, it.y, it.z) } else null
                victims.forEach { gameplay.cameraShake(castRef(run), it, d("intensity"), ticks, from) }
            }
            "stop_sound" -> victims.forEach { gameplay.stopSound(castRef(run), it, s("sound")) }
            "body" -> {
                val id = row.instance
                run.recs[id]?.let { endInstance(run, id) }
                // the clip lives as long as its instance: End / state exit / cast end all go through endInstance
                run.recs[id] = Rec(SpawnKind.FRAME, null, "", now, ctx.owner, PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0)))
                run.cursor.instances[id] = GatewayHandle(nextSynthetic--)
                val anim = s("anim")
                run.bodyClips[id] = anim
                gameplay.playBodyClip(castRef(run), caster, anim, d("speed"))
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
            radius = dbl(run, cell("radius"), ctx),
            stopBeforeTarget = p["stop_before_target"]?.let { dbl(run, it, ctx) },
        )
        run.dash.start(row.instance.text, params)?.let { fault(run, now, src, it) }
    }

    private fun reelStart(run: Run, row: Row, p: Map<String, Val<Any>>, ctx: RowCtx, now: Int, src: String) {
        fun cell(k: String): Val<Any> = p[k] ?: throw IllegalArgumentException("effect 'reel' needs '$k'")
        val frame = any(run, cell("frame"), ctx) as InstanceId
        val to = any(run, cell("to"), ctx) as InstanceId
        val rec = run.recs[frame]
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
     * `rig` (v2 `RigExecutor` / `RigPerformer` at 93cd534e4~1): the swing drives the frame `of` (absent = `parent`), whose local pose in
     * the parent's axes is the grip origin and the arm-carried wrist orientation each tick. A clip (`anim`) plays on the caster's own body
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
        val track = clip?.let { RigBodyTrack(it, slim) }
        @Suppress("UNCHECKED_CAST")
        val swing = (any(run, cell("swing"), ctx) as List<Map<String, Any>>).map { m ->
            RigWristKey((m["t"] as Number).toInt(), (m["rotate"] as? Vector3d)?.let { Vec3(it.x, it.y, it.z) }, m["ease"]?.toString() ?: "linear", m["sample"] as? Boolean ?: false)
        }
        val freezes = (any(run, cell("freezes"), ctx) as List<*>).map { f ->
            val m = f as Map<*, *>
            RigFreezeWindow((m["at"] as Number).toInt(), (m["ticks"] as Number).toInt())
        }
        fun v3(k: String): Vec3 = (any(run, cell(k), ctx) as Vector3d).let { Vec3(it.x, it.y, it.z) }
        val hand = bool(run, cell("hand"), ctx)
        val sampleTicks = (any(run, cell("sample_ticks"), ctx) as Number).toInt()
        val respect = bool(run, cell("respect_hit_stop"), ctx)
        val bodyRespect = bool(run, cell("body_respect_hit_stop"), ctx)
        fun motion(hitStop: Boolean) = RigMotion(swing, v3("tilt"), v3("rest"), v3("pivot"), hand, sampleTicks, freezes, hitStop, track, slim)
        val wrist = motion(respect)
        val bodyClock = if (bodyRespect && !respect) motion(true) else wrist
        if (anim != null) gameplay.playBodyClip(castRef(run), caster, anim, 1.0)
        var held: Rec? = null
        val driven: InstanceId
        if (of != null) {
            driven = of
        } else {
            // no `of`: the rig is its own frame (v2 `RigPerformer.start`, 93cd534e4~1) and the caster's held item rides it as the part (R27).
            // The frame is carried by the arm; the part takes only the fixed pivot and the wrist orientation (`withPartFrame`).
            driven = row.instance
            if (run.recs[driven] != null) endInstance(run, driven)
            val parentRec = run.recs[parent]
            val identity = PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0))
            run.recs[driven] = Rec(SpawnKind.FRAME, parentRec?.let { parent }, if (parentRec == null) parent.text else "", now, ctx.owner, identity)
            run.cursor.instances[driven] = GatewayHandle(nextSynthetic--)
            val heldId = InstanceId(driven.path + InstanceId.Segment(OpId("held"), null))
            val pivot = v3("pivot")
            val local = PoseV(Vector3d(pivot.x, pivot.y, pivot.z), rotOf(wrist.rotationAt(wrist.clock(0))), Vector3d(SkinRigTransforms.PLAYER_SCALE))
            val rec = Rec(SpawnKind.ITEM, parentRec?.let { parent }, if (parentRec == null) parent.text else "", now, ctx.owner, local)
            val h = visual.spawnMesh(
                castRef(run), MeshModels.MAIN_HAND, null, visualPose(rec, worldOf(run, rec, local, ctx.binding, now)), null, MeshLook(rigShading = true),
            )
            rec.real = h
            run.recs[heldId] = rec
            run.cursor.instances[heldId] = GatewayHandle(h.id)
            held = rec
        }
        val frameRec = run.recs.getValue(driven)
        for (k in listOf("tip", "root", "edge")) p[k]?.let { frameRec.points[k] = any(run, it, ctx) as Vector3d }
        val pivot = v3("pivot")
        val rig = RigRec(driven, now, wrist, bodyClock, anim, caster, held, Vector3d(pivot.x, pivot.y, pivot.z))
        run.rigs[row.instance] = rig
        rigPose(run, rig, now)
    }

    private fun rotOf(q: Quaternion): Quaterniond = Quaterniond(q.x, q.y, q.z, q.w)

    private fun rigPose(run: Run, rig: RigRec, now: Int) {
        val rec = run.recs[rig.driven] ?: return
        val elapsed = now - rig.start
        val s = rig.wrist.clock(elapsed)
        val bodyS = rig.bodyClock.clock(elapsed)
        val origin = rig.wrist.originAt(bodyS)
        val q = rig.wrist.frameRotationAt(s, bodyS)
        val cur = rec.localAt(now)
        val target = PoseV(Vector3d(origin.x, origin.y, origin.z), Quaterniond(q.x, q.y, q.z, q.w), Vector3d(cur.scale))
        rec.moveTo(target, now, 0)
        rec.real?.let { visual.write(it, visualPose(rec, worldOf(run, rec, target, null, now)), 0) }
        rig.held?.let { h ->
            val pose = PoseV(Vector3d(rig.pivot), rotOf(rig.wrist.rotationAt(s)), Vector3d(h.localAt(now).scale))
            h.moveTo(pose, now, 0)
            h.real?.let { visual.write(it, visualPose(h, worldOf(run, h, pose, null, now)), 0) }
        }
    }

    private fun driversStep(run: Run, now: Int) {
        run.dash.tick()
        for ((id, reel) in run.reels.entries.toList()) reelStep(run, id, reel, now)
        for (rig in run.rigs.values.toList()) rigPose(run, rig, now)
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
            emit(run, EventPath(id.text + ".end"), now, null)
        }
    }

    private fun rigStop(run: Run, rig: RigRec) {
        rig.anim?.let { gameplay.stopBodyClip(castRef(run), rig.caster, it) }
    }

    private fun hasDriver(run: Run, id: InstanceId): Boolean =
        id in run.reels || id in run.rigs || run.dash.isActive(id.text) || run.reel.isActive(id.text)

    private fun endDrivers(run: Run, id: InstanceId) {
        run.dash.end(id.text)
        run.reel.stop(id.text)
        run.reels.remove(id)
        run.rigs.remove(id)?.let { rigStop(run, it) }
    }

    private fun stopDrivers(run: Run) {
        run.dash.cancelAll()
        run.reel.stopAll()
        run.reels.clear()
        run.rigs.values.toList().forEach { rigStop(run, it) }
        run.rigs.clear()
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
     * `align:<axis>><to>^<up>` (R42, v2 `MotionComposer.alignedRotation`): the LOCAL rotation that points the object's [axis] along the
     * named direction `to` with its up toward `up`, i.e. `Basis.facing(dir, up)` in world, expressed in the parent's space
     * (`parentWorld.conjugate * world`). Directions are `world.up`, `world.down` and `<instance|caster>.<axis>` (v2 `Axes.resolve`);
     * a bare anchor id is rejected at load (v2 produced no rotation for it), so every `to` here carries a dot.
     */
    private fun alignRot(run: Run, cell: String, ctx: RowCtx, anchor: InstanceId?, anchorName: String): Quaterniond {
        val body = cell.removePrefix("align:")
        val axisName = body.substringBefore('>')
        val to = body.substringAfter('>').substringBefore('^')
        val up = body.substringAfter('^')
        val parent = anchor?.let { run.recs[it] }?.let { worldOf(run, it, it.localAt(lastTick), ctx.binding, lastTick) }
            ?: if (anchorName.isNotEmpty() && anchorName != "world") PoseV(live.vec(run.cursor.handle, anchorName, ctx.binding, ctx.scope), live.rot(run.cursor.handle, anchorName, ctx.binding, ctx.scope), Vector3d(1.0, 1.0, 1.0)) else PoseV(Vector3d(), Quaterniond(), Vector3d(1.0, 1.0, 1.0))
        val dir = namedDirection(run, to, ctx)
        val upDir = namedDirection(run, up, ctx)
        val objAxis = LocalAxes.axis(axisName.removePrefix("-"))?.let { if (axisName.startsWith("-")) it * -1.0 else it }
            ?: throw IllegalArgumentException("align axis '$axisName' cannot be resolved")
        val z = Vec3(0.0, 0.0, 1.0)
        val a = objAxis.normalize()
        val toZ = when {
            a.dot(z) > 1.0 - 1e-12 -> Quaternion.IDENTITY
            a.dot(z) < -1.0 + 1e-12 -> Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.PI)
            else -> Quaternion.axisAngle(a.cross(z), Math.acos(a.dot(z).coerceIn(-1.0, 1.0)))
        }
        val w = Basis.facing(dir, upDir) * toZ
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
            val (rec, _) = anchorOf(run, name) ?: throw IllegalArgumentException("align target '$name': frame '$inst' has no edge point")
            val d = rec.points.getValue("edge")
            val r = worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick).rot
            return Vector3d(d).rotate(r).let { Vec3(it.x, it.y, it.z) }
        }
        val rot = if (inst == "caster") live.rot(run.cursor.handle, "caster", ctx.binding, ctx.scope)
        else anchorOf(run, inst)?.let { (rec, _) -> worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick).rot }
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
    private fun anchorOf(run: Run, name: String): Pair<Rec, String>? {
        val bare = name.removePrefix("anchor:")
        for (part in listOf("position", "rotation", "forward", "tip", "root", "edge", "")) {
            val base = if (part.isEmpty()) bare else if (bare.endsWith(".$part")) bare.removeSuffix(".$part") else continue
            val id = run.recs.keys.firstOrNull { it.text == base } ?: continue
            val rec = run.recs.getValue(id)
            if ((part == "tip" || part == "root" || part == "edge") && part !in rec.points) continue
            return rec to part
        }
        return null
    }

    private fun liveVec(run: Run, name: String, ctx: RowCtx): Vector3d {
        paramOf(run, name)?.let { return toVector(it, name) }
        anchorOf(run, name)?.let { (rec, part) ->
            if (part == "rotation") throw IllegalArgumentException("'$name' is a rotation, not a vector")
            val w = worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick)
            if (part == "tip" || part == "root") return Vector3d(rec.points.getValue(part)).rotate(w.rot).add(w.pos)
            // `edge` is a direction with a zero local origin (v2 `FramePoint(frame, "edge", Vec3.ZERO, dir)`): as a point it is the frame origin
            return if (part == "forward") Vector3d(0.0, 0.0, 1.0).rotate(w.rot) else w.pos
        }
        return live.vec(run.cursor.handle, name, ctx.binding, ctx.scope)
    }

    private fun liveRot(run: Run, name: String, ctx: RowCtx): Quaterniond {
        // the turn source of a projectile body is not carried by the cell: no extra turn
        if (name == "motion.turn") return Quaterniond()
        paramOf(run, name)?.let { v -> return (v as? Quaterniond)?.let { Quaterniond(it) } ?: throw IllegalArgumentException("param '$name' is not a rotation") }
        anchorOf(run, name)?.let { (rec, part) ->
            if (part != "rotation" && part.isNotEmpty()) throw IllegalArgumentException("'$name' is a ${part}, not a rotation")
            return worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick).rot
        }
        return live.rot(run.cursor.handle, name, ctx.binding, ctx.scope)
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

    private fun dbl(run: Run, v: Val<Any>, ctx: RowCtx): Double = (any(run, v, ctx) as Number).toDouble()

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
            else live.targets(run.cursor.handle, TargetRule.Entity("event.victim"), ctx.binding).entities.map { it.id }
    }

    /** The seed `V3Bindings.seed(castSeed, op, i)` the compiler folds with, and the copy indices of the instance path (innermost first). */
    /**
     * The seed a row guard draws with: (cast seed, row instance + source, firing tick, event index, victim). Each firing of a row draws
     * independently (v2 `rollChance` was one fresh draw per application), and a replay of the cast draws the same values.
     */
    private fun guardSeed(run: Run, row: Row, ctx: RowCtx, now: Int): Long {
        var h = rowScope(run, row).seed xor (row.src.value.hashCode().toLong() * -0x61c8864680b583ebL)
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = h xor (now.toLong() * 0x2545F4914F6CDD1DL)
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        h = h xor ((ctx.binding?.index ?: -1).toLong() * 0x1B873593L)
        h = h xor ((ctx.binding?.victim?.id?.hashCode() ?: 0).toLong() * -0x61c8864680b583ebL)
        return h xor (h ushr 31)
    }

    private fun rowScope(run: Run, row: Row): RowScope {
        val last = row.instance.path.lastOrNull() ?: return RowScope.cast(run.request.seed)
        val indices = row.instance.path.mapNotNull { it.index }.asReversed()
        return RowScope(V3Bindings.seed(run.request.seed, last.op, last.index ?: 0), run.request.seed, indices)
    }

    private fun rowStream(run: Run, row: Row, ctx: RowCtx): SeededStream =
        run.cursor.seed.fork("${row.instance.text}|${row.src.value}|${ctx.base}|${row.tick}")

    private fun castRef(run: Run): CastRef = CastRef(run.cursor.handle.value, run.request.caster, run.request.world)

    private fun fault(run: Run, tick: Int, src: String, message: String) {
        faultLog += PlayerFault(run.cursor.handle, tick, src, message)
    }

    // ------------------------------------------------------------------ state

    /**
     * A projectile body's flight: [start] + [dir] * the integral of [speed] over the ticks since spawn (closed form). With [gravity] / [drag]
     * (v2 FlyLaw 1:1, `9d4c4a3db:.../motion/FlyLaw.kt`: each tick `v = (v - (0, gravity, 0)) * (1 - drag)`, then the body moves by `v`) the launch
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

    private class RigRec(val driven: InstanceId, val start: Int, val wrist: RigMotion, val bodyClock: RigMotion, val anim: String?, val caster: UUID, val held: Rec?, val pivot: Vector3d)

    private class ShapeFrame(val frame: String, val from: String, val to: String)

    private class LockRec(val owner: StatePath?, val move: LockMove, val pitch: Boolean, val yaw: Boolean, val untilAbs: Int)

    /** A live thing: its local pose moves along one interpolation segment (closed form, same as the client's). */
    private class Rec(
        val kind: SpawnKind,
        val anchor: InstanceId?,
        val anchorName: String,
        val spawnTick: Int,
        val owner: StatePath?,
        var to: PoseV,
    ) {
        var real: VisualHandle? = null
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
        var blockDespawn: Boolean = false
        var billboard: DisplayBillboard = DisplayBillboard.FIXED
        /** `frame axes`: which rotation a root frame takes (null = its anchor's own). */
        var axes: String? = null

        /** Ticks this body spent frozen: its flight clock runs `frozenTicks` behind the wall clock. */
        var frozenTicks: Int = 0

        /** Rig frame points (v2 `FramePoint`): `tip` / `root` local offsets, `edge` a direction at the frame origin. */
        val points: MutableMap<String, Vector3d> = HashMap()
        private var from: PoseV = to
        private var segStart: Int = spawnTick
        private var segDur: Int = 0

        fun moveTo(target: PoseV, now: Int, duration: Int) {
            from = localAt(now)
            to = target
            segStart = now
            segDur = duration
        }

        fun localAt(t: Int): PoseV {
            if (segDur <= 0 || t >= segStart + segDur) return to
            if (t <= segStart) return from
            val f = (t - segStart).toDouble() / segDur
            return PoseV(
                Vector3d(from.pos).lerp(to.pos, f), Quaterniond(from.rot).slerp(to.rot, f), Vector3d(from.scale).lerp(to.scale, f),
            )
        }
    }

    private class Run(
        val cursor: CastCursor, val request: CastRequest, val states: Map<StatePath, StateTable>, val listened: Set<EventPath>,
        val dash: DashDriver,
    ) {
        val reel = ReelDriver()
        val reels = LinkedHashMap<InstanceId, ReelRec>()
        val rigs = LinkedHashMap<InstanceId, RigRec>()
        val recs = LinkedHashMap<InstanceId, Rec>()
        val pending = ArrayList<RuntimeEvent>()
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
        val bodyClips = HashMap<InstanceId, String>()

        /** Final regions seen per parallel parent (`<parent>.end` is published when all are final). */
        val regionEnds = HashMap<StatePath, Int>()

        /** Per-instance `set` overrides of `count` / `volume`, read by that instance's later particle / sound rows. */
        val fields = HashMap<InstanceId, HashMap<String, Double>>()

        /** `hit` re-hit window `every N`: tick of the last hit per (instance, victim). */
        val hitLast = HashMap<InstanceId, HashMap<UUID, Int>>()
        var inTick = false
        var finishing = false
        var finalized = false
    }
}
