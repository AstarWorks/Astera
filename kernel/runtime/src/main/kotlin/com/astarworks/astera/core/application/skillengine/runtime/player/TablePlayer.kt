package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.application.skillengine.runtime.CastError
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
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
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skill.value.NumericValue
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
import com.astarworks.astera.core.domain.skillengine.table.HitShape
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
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
import org.joml.Quaterniond
import org.joml.Vector3d
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
    public fun guard(cast: CastHandle, guard: Guard, conditions: Map<String, String>, binding: EventBinding?, localTick: Int): Boolean? = null

    /** Starts holding the caster's inputs until server tick [untilTick] (absolute); false = unsupported. */
    public fun lock(caster: UUID, move: Boolean, pitch: Boolean, yaw: Boolean, untilTick: Int): Boolean = false

    public fun unlock(caster: UUID, move: Boolean, pitch: Boolean, yaw: Boolean) {}

    /**
     * One `set` row (item 15c) for a field a display handle carries and the visual gateway has no method for (`text_opacity`).
     * false = unsupported. `count` and `volume` never reach here: the player folds them into the instance's later particle / sound rows.
     */
    public fun setDisplayField(handle: VisualHandle, field: String, value: Double): Boolean = false
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
        runs[handle.value] = Run(cursor, request, states, listened)
        lastTick = max(lastTick, request.startTick)
        return Result.Success(handle)
    }

    override fun tick(now: Int) {
        lastTick = now
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
        val binding = EventBinding(ev.path, ev.index, ev.contact, ev.victim)
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
        val r = hooks.guard(run.cursor.handle, g, run.cursor.skill.table.conditions, binding, lt)
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
        run.pending += RuntimeEvent(path, now, binding?.index, binding?.contact, binding?.victim)
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
                if (scope.id !in run.recs) return
                endInstance(run, scope.id)
                emit(run, EventPath(scope.id.text + ".end"), now, ctx?.binding)
            }
        }
    }

    // ------------------------------------------------------------------ instances

    private fun endInstance(run: Run, id: InstanceId) {
        val prefix = id.path
        val doomed = run.recs.keys.filter { k -> k.path.size >= prefix.size && k.path.subList(0, prefix.size) == prefix }
        for (k in doomed.asReversed()) {
            val rec = run.recs.remove(k) ?: continue
            run.cursor.instances.remove(k)
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
            pp = live.vec(run.cursor.handle, rec.anchorName, binding)
            pr = live.rot(run.cursor.handle, rec.anchorName, binding)
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

    private class RowCtx(val base: Int, val binding: EventBinding?, val owner: StatePath?, val level: LevelCursor?)

    private fun playRow(run: Run, row: Row, ctx: RowCtx, now: Int) {
        val src = row.src.value.ifEmpty { row.instance.text }
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
                is Action.Effect -> effect(run, row, a, ctx, now, src)                is Action.Lock -> {
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
                        fault(run, now, src, "gated skill call '${c.id}' needs a cooldown / cost gateway (not in TablePlayer's ports)")
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
        val local = PoseV(vec(run, a.pose.position, ctx), quat(run, a.pose.rotation, ctx), vec(run, a.pose.scale, ctx))
        // a body's spawn pose is already the origin anchor's WORLD pose (`anchor:<origin>.position`); anchoring it again would count the anchor twice
        val body = a.kind == SpawnKind.PROJECTILE_BODY
        val rec = Rec(a.kind, if (body) null else a.anchor, if (body) "" else a.anchorName, now, ctx.owner, local)
        rec.billboard = a.look.fields["billboard"]?.let { runCatching { DisplayBillboard.valueOf(strOf(run, it, ctx).uppercase()) }.getOrNull() } ?: DisplayBillboard.FIXED
        run.recs[id] = rec
        if (body) {
            rec.flight = flightOf(run, a.look, local, ctx)
            val lf = a.look.fields
            val mode = lf["block.mode"]?.let { strOf(run, it, ctx) }
            val stuck = if (mode == "stick") lf["block.for"]?.let { dbl(run, it, ctx).toInt() } ?: 0 else 0
            lf["end.max"]?.let { rec.expireTick = now + dbl(run, it, ctx).toInt() - stuck }
            rec.hitTargets = lf["hit.targets"]?.let { strOf(run, it, ctx) } ?: ""
            rec.hitRadius = lf["radius"]?.let { dbl(run, it, ctx) } ?: 0.0
            val once = lf["hit.once"]?.let { any(run, it, ctx) } == true
            rec.hitTimes = if (once) 1 else lf["hit.times"]?.let { dbl(run, it, ctx).toInt() } ?: 1
            rec.endsOn = buildSet {
                if (lf["hit.once"]?.let { any(run, it, ctx) } == true) add("hit")
                if (mode == "despawn" || mode == "stick") add("block")
            }
        }
        val world = worldOf(run, rec, local, ctx.binding, now)
        val pose = visualPose(rec, world)
        val owner = run.request.caster
        val look = a.look
        val brightness = look.fields["brightness"]?.let { int(run, it as Val<Int>, ctx) }
        val glow = look.fields["glow"]?.let { strOf(run, it, ctx) }
        val h: VisualHandle? = when (a.kind) {
            SpawnKind.FRAME, SpawnKind.PROJECTILE_BODY -> null
            SpawnKind.ITEM -> visual.spawnMesh(
                owner, req(run, look, "item", ctx), look.fields["part"]?.let { strOf(run, it, ctx) }, pose, brightness,
                MeshLook(
                    itemModel = look.fields["model"]?.let { strOf(run, it, ctx) },
                    mode = look.fields["mode"]?.let { DisplayItemMode.valueOf(strOf(run, it, ctx).uppercase()) } ?: DisplayItemMode.FIXED,
                    rigShading = look.fields["rig_shading"]?.let { any(run, it, ctx) as? Boolean } ?: true,
                ),
                glow = glow,
            )
            SpawnKind.BLOCK -> visual.spawnBlock(owner, req(run, look, "block", ctx), pose, brightness, glow = glow)
            SpawnKind.TEXT -> visual.spawnText(
                owner, req(run, look, "text", ctx), look.fields["background"]?.let { strOf(run, it, ctx) } ?: "#000000",
                look.fields["background_alpha"]?.let { dbl(run, it, ctx) } ?: 0.0, flag(run, look, "shadow", ctx), flag(run, look, "see_through", ctx), pose, brightness,
            )
            SpawnKind.SPRITE -> visual.spawnPlate(
                owner, req(run, look, "sprite", ctx), look.fields["alpha"]?.let { dbl(run, it, ctx) } ?: 1.0,
                look.fields["tint"]?.let { strOf(run, it, ctx) }, flag(run, look, "see_through", ctx), pose, brightness, glow = glow,
            )
            SpawnKind.RIBBON -> visual.spawnTrail(
                owner, dbl(run, look.fields["ticks"] ?: throw IllegalArgumentException("ribbon look needs 'ticks'"), ctx).toInt(),
                look.fields["width"]?.let { dbl(run, it, ctx) } ?: 1.0, look.fields["color"]?.let { strOf(run, it, ctx) } ?: "#ffffff",
                look.fields["alpha"]?.let { dbl(run, it, ctx) } ?: 1.0,
            ).also { th ->
                visual.setTrailLook(th, look.fields["fade_power"]?.let { dbl(run, it, ctx) } ?: 1.0, look.fields["style"]?.let { strOf(run, it, ctx) } ?: "soft")
            }
        }
        val gh = if (h != null) GatewayHandle(h.id) else GatewayHandle(nextSynthetic--)
        rec.real = h
        run.cursor.instances[id] = gh
    }

    private fun key(run: Run, a: Action.Key, ctx: RowCtx, now: Int) {
        val rec = run.recs[a.display] ?: return
        val target = PoseV(vec(run, a.translation, ctx), quat(run, a.leftRotation, ctx), vec(run, a.scale, ctx))
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
        sounds.play(a.sound, Vec3(p.x, p.y, p.z), a.category, volume, num(run, a.pitch, ctx))
    }

    /** A named custom particle definition at a position; `count` is the definition's own unless a `set` row overrode it. */
    private fun particleDef(run: Run, row: Row, a: Action.ParticleDef, ctx: RowCtx) {
        val pos = vec(run, a.position, ctx)
        val req = run.request
        particles.emit(
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

    // hit: broad phase from the query, narrow phase from the domain resolver, swept along the frame's last-tick displacement (no tunnelling)
    private fun hit(run: Run, row: Row, a: Action.Hit, ctx: RowCtx, now: Int) {
        val caster = run.request.caster
        val delta = a.anchor?.let { run.recs[it] }?.let { r ->
            val cur = worldOf(run, r, r.localAt(now), ctx.binding, now).pos
            val prev = worldOf(run, r, r.localAt(now - 1), ctx.binding, now - 1).pos
            Vector3d(cur).sub(prev)
        } ?: Vector3d()
        fun back(p: Vector3d, k: Double) = Vec3(p.x - delta.x * k, p.y - delta.y * k, p.z - delta.z * k)
        var sweepRadius: Double
        val volumes: (Double) -> ResolvedVolume = when (val s = a.shape) {
            is HitShape.Sphere -> {
                val c = vec(run, s.center, ctx)
                val r = num(run, s.radius, ctx)
                sweepRadius = r
                ({ k -> ResolvedVolume.Sphere(back(c, k), r) })
            }
            is HitShape.Capsule -> {
                val f = vec(run, s.from, ctx)
                val t = vec(run, s.to, ctx)
                val r = num(run, s.radius, ctx)
                sweepRadius = r
                ({ k -> ResolvedVolume.Capsule(back(f, k), back(t, k), r) })
            }
            is HitShape.Sector -> {
                val o = vec(run, s.origin, ctx)
                val fwd = vec(run, s.forward, ctx).normalize()
                val up = if (kotlin.math.abs(fwd.y) > 0.999) Vector3d(0.0, 0.0, -1.0) else Vector3d(0.0, 1.0, 0.0)
                // the domain derives up = forward x left, so left = up x forward
                val left = Vector3d(up).cross(fwd).normalize()
                val deg = num(run, s.deg, ctx)
                val range = num(run, s.range, ctx)
                val height = num(run, s.height, ctx)
                sweepRadius = range
                ({ k ->
                    ResolvedVolume.Sector(back(o, k), Vec3(fwd.x, fwd.y, fwd.z), Vec3(left.x, left.y, left.z), deg, range, height)
                })
            }
            is HitShape.Box -> {
                val c = vec(run, s.center, ctx)
                val h = vec(run, s.halfExtents, ctx)
                val rot = quat(run, s.rotation, ctx)
                sweepRadius = min(h.x, min(h.y, h.z))
                ({ k -> ResolvedVolume.Box(back(c, k), Vec3(h.x, h.y, h.z), Quaterniond(rot)) })
            }
        }
        val rule = a.targets
        val allowed = ruleTargets(run, rule, ctx).toSet()
        // item 14.7: poses = min(maxSweepSteps, ceil(travel / radius) + 1); the last one is the current tick
        val travel = delta.length()
        val poses = if (travel < 1e-9) 1 else min(config.maxSweepSteps.toDouble(), ceil(travel / max(sweepRadius, 1e-6)) + 1.0).toInt().coerceAtLeast(1)
        val seen = LinkedHashMap<UUID, Pair<Vec3, Vec3>>()
        val once = run.hitOnce.getOrPut(row.instance) { HashSet() }
        val last = run.hitLast.getOrPut(row.instance) { HashMap() }
        val window = a.window
        fun blocked(victim: UUID): Boolean = when (window) {
            HitWindow.Once -> victim in once
            is HitWindow.Every -> last[victim]?.let { now - it < window.ticks } ?: false
        }
        val excluded = if ((rule is TargetRule.Entity && rule.ref == "caster") || (rule is TargetRule.Area && rule.includeSelf)) null else caster
        for (i in 0 until poses) {
            val k = if (poses == 1) 0.0 else (poses - 1 - i).toDouble() / (poses - 1)
            val vol = volumes(k)
            val cands = query.nearby(vol.broadCenter, vol.broadRadius, excluded).filter { it.entityId in allowed && it.entityId !in seen }
            for (ce in resolver.narrow(row.instance.text, now, row.instance.text, vol, Vec3(delta.x, delta.y, delta.z), cands)) {
                if (blocked(ce.victim)) continue
                seen.putIfAbsent(ce.victim, ce.pointWorld to ce.normalWorld)
            }
        }
        val comps = a.damage.map { c -> SkillDamage(c.attribute.name.lowercase(), literalAmount(c)) }
        for ((victim, pn) in seen) {
            once += victim
            last[victim] = now
            if (comps.isNotEmpty()) gameplay.damage(run.cursor.skill.def.id, caster, victim, comps, pn.first, false, false)
            run.pending += RuntimeEvent(
                EventPath(row.instance.text + ".hit"), now, row.instance.path.lastOrNull()?.index,
                ContactInfo(Vector3d(pn.first.x, pn.first.y, pn.first.z), Vector3d(pn.second.x, pn.second.y, pn.second.z)), EntityRef(victim),
            )
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
            rec.flight?.let { fl ->
                val cur = rec.localAt(now)
                rec.moveTo(PoseV(fl.at(now - rec.spawnTick), cur.rot, cur.scale), now, 0)
            }
            val blocked = blockContact(run, id, rec, now)
            if (!blocked) entityContact(run, id, rec, now)
            // R16: `expire` is the end by range / `for`, just before `end`; a hit / block end (rec.ended) never expires (checked at delivery)
            if (rec.expireTick == now) run.pending += RuntimeEvent(EventPath(id.text + ".expire"), now, id.path.lastOrNull()?.index, null, null)
        }
    }

    /** Fires `<body>.block` for a block on this tick's segment; true when that block ends the flight (despawn / stick), so no entity is tested behind it. */
    private fun blockContact(run: Run, id: InstanceId, rec: Rec, now: Int): Boolean {
        val path = EventPath(id.text + ".block")
        if (path !in run.listened) return false
        val cur = worldOf(run, rec, rec.localAt(now), null, now).pos
        val prev = worldOf(run, rec, rec.localAt(now - 1), null, now - 1).pos
        if (cur.distanceSquared(prev) < 1e-12) return false
        val b = query.raycastBlock(Vec3(prev.x, prev.y, prev.z), Vec3(cur.x, cur.y, cur.z)) ?: return false
        run.pending += RuntimeEvent(
            path, now, id.path.lastOrNull()?.index, ContactInfo(Vector3d(b.point.x, b.point.y, b.point.z), Vector3d(b.normal.x, b.normal.y, b.normal.z)), null,
        )
        return "block" in rec.endsOn
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
        if (rec.hitTargets != "enemies" && rec.hitTargets != "all") {
            fault(run, now, id.text, "projectile hit.targets '${rec.hitTargets}': no team port in the query (only enemies / all)")
            rec.hitTimes = 0
            return
        }
        val cur = worldOf(run, rec, rec.localAt(now), null, now).pos
        val prev = worldOf(run, rec, rec.localAt(now - 1), null, now - 1).pos
        val delta = Vec3(cur.x - prev.x, cur.y - prev.y, cur.z - prev.z)
        val vol = ResolvedVolume.Capsule(Vec3(prev.x, prev.y, prev.z), Vec3(cur.x, cur.y, cur.z), rec.hitRadius)
        val cands = query.nearby(vol.broadCenter, vol.broadRadius, run.request.caster).filter { it.entityId !in rec.hitVictims }
        val start = Vec3(prev.x, prev.y, prev.z)
        val found = resolver.narrow(id.text, now, id.text, vol, delta, cands).sortedBy { it.pointWorld.distance(start) }
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
            // the flight ends with its last permitted victim: `end` right after `hit`, the body goes at cleanup
            rec.ended = true
            run.pending += RuntimeEvent(EventPath(id.text + ".end"), now, id.path.lastOrNull()?.index, null, null)
            if (id !in run.pendingEnds) run.pendingEnds += id
        }
    }

    private fun damage(run: Run, row: Row, a: Action.Damage, ctx: RowCtx, now: Int) {
        val comps = a.components.map { c -> SkillDamage(c.attribute.name.lowercase(), literalAmount(c)) }
        for (v in ruleTargets(run, a.targets, ctx)) {
            val point = ctx.binding?.contact?.point?.let { Vec3(it.x, it.y, it.z) } ?: query.rootOf(v)?.feet ?: Vec3.ZERO
            gameplay.damage(run.cursor.skill.def.id, run.request.caster, v, comps, point, false, false)
        }
    }

    private fun literalAmount(c: DamageComponent): Double {
        require(c.base is NumericValue.Literal && c.multiplier is NumericValue.Literal) { "damage component is not a compile-time literal" }
        return c.constantValue
    }

    private fun effect(run: Run, row: Row, a: Action.Effect, ctx: RowCtx, now: Int, src: String) {
        val p = a.payload
        val kind = p["kind"]?.let { strOf(run, it, ctx) } ?: a.op.value
        val victims = p["targets"]?.let { v ->
            @Suppress("UNCHECKED_CAST")
            targets(run, v as Val<TargetSet>, ctx)
        } ?: listOf(run.request.caster)
        fun cell(key: String): Val<Any> = p[key] ?: throw IllegalArgumentException("effect '$kind' needs '$key'")
        fun d(key: String): Double = dbl(run, cell(key), ctx)
        fun s(key: String): String = strOf(run, cell(key), ctx)
        fun v(key: String): Vector3d = any(run, cell(key), ctx) as Vector3d
        val caster = run.request.caster
        when (kind) {
            "dash", "dash.end", "reel", "reel.stop", "rig" ->
                fault(run, now, src, "effect '$kind' needs the caster-motion gateway (not in TablePlayer's ports yet; w1b-02i)")
            "impulse", "knockback" -> {
                val vv = v("vector")
                victims.forEach { gameplay.impulse(it, Vec3(vv.x, vv.y, vv.z)) }
            }
            "resource" -> victims.forEach { gameplay.restoreResource(it, s("resource"), d("amount")) }
            "heal" -> victims.forEach { gameplay.restoreResource(it, "health", d("amount")) }
            "modify_attribute" -> {
                @Suppress("UNCHECKED_CAST")
                val dur = p["for"]?.let { int(run, it as Val<Int>, ctx) }
                victims.forEach { gameplay.modifyAttribute(caster, it, s("attribute"), s("operation"), d("amount"), dur) }
            }
            else -> fault(run, now, src, "effect '$kind' has no handler in TablePlayer")
        }
    }

    // ------------------------------------------------------------------ Val.Live resolution (C4c)

    private fun vec(run: Run, v: Vec, ctx: RowCtx): Vector3d = when (v) {
        is Val.Known -> Vector3d(v.value)
        is Val.Live -> liveVec(run, v.name, ctx)
    }

    private fun quat(run: Run, v: Rot, ctx: RowCtx): Quaterniond = when (v) {
        is Val.Known -> Quaterniond(v.value)
        is Val.Live -> liveRot(run, v.name, ctx)
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
        for (part in listOf("position", "rotation", "")) {
            val base = if (part.isEmpty()) bare else if (bare.endsWith(".$part")) bare.removeSuffix(".$part") else continue
            val id = run.recs.keys.firstOrNull { it.text == base } ?: continue
            return run.recs.getValue(id) to part
        }
        return null
    }

    private fun liveVec(run: Run, name: String, ctx: RowCtx): Vector3d {
        paramOf(run, name)?.let { return toVector(it, name) }
        anchorOf(run, name)?.let { (rec, part) ->
            if (part == "rotation") throw IllegalArgumentException("'$name' is a rotation, not a vector")
            return worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick).pos
        }
        return live.vec(run.cursor.handle, name, ctx.binding)
    }

    private fun liveRot(run: Run, name: String, ctx: RowCtx): Quaterniond {
        // the turn source of a projectile body is not carried by the cell: no extra turn
        if (name == "motion.turn") return Quaterniond()
        paramOf(run, name)?.let { v -> return (v as? Quaterniond)?.let { Quaterniond(it) } ?: throw IllegalArgumentException("param '$name' is not a rotation") }
        anchorOf(run, name)?.let { (rec, part) ->
            if (part == "position") throw IllegalArgumentException("'$name' is a position, not a rotation")
            return worldOf(run, rec, rec.localAt(lastTick), ctx.binding, lastTick).rot
        }
        return live.rot(run.cursor.handle, name, ctx.binding)
    }

    private fun liveNum(run: Run, name: String, ctx: RowCtx): Double {
        paramOf(run, name)?.let { v ->
            return when (v) {
                is Number -> v.toDouble()
                is Boolean -> if (v) 1.0 else 0.0
                else -> throw IllegalArgumentException("param '$name' is not a number")
            }
        }
        return live.num(run.cursor.handle, name, ctx.binding)
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
        // a `.hit` event of this cast is always the caster hitting
        rule is TargetRule.Entity && rule.ref == "event.attacker" -> listOf(run.request.caster)
        rule is TargetRule.Area -> areaTargets(run, rule, ctx)
        else -> live.targets(run.cursor.handle, rule, ctx.binding).entities.map { it.id }
    }

    private fun areaTargets(run: Run, rule: TargetRule.Area, ctx: RowCtx): List<UUID> {
        if (rule.kind != "enemies") throw IllegalArgumentException("area target kind '${rule.kind}': only 'enemies' is resolvable (no team port in the query)")
        val c = rule.around?.let { id -> run.recs[id]?.let { worldOf(run, it, it.localAt(lastTick), ctx.binding, lastTick).pos } }
            ?: liveVec(run, rule.aroundName, ctx)
        val center = Vec3(c.x, c.y, c.z)
        val found = query.nearby(center, num(run, rule.radius, ctx), if (rule.includeSelf) null else run.request.caster)
            .sortedWith(compareBy({ it.feet.distance(center) }, { it.entityId }))
        val limited = if (rule.maxCount > 0) found.take(rule.maxCount) else found
        return limited.map { it.entityId }
    }

    private fun dbl(run: Run, v: Val<Any>, ctx: RowCtx): Double = (any(run, v, ctx) as Number).toDouble()

    private fun strOf(run: Run, v: Val<Any>, ctx: RowCtx): String = any(run, v, ctx).toString()

    private fun req(run: Run, look: Look, key: String, ctx: RowCtx): String =
        strOf(run, look.fields[key] ?: throw IllegalArgumentException("look needs '$key'"), ctx)

    private fun flag(run: Run, look: Look, key: String, ctx: RowCtx): Boolean = look.fields[key]?.let { any(run, it, ctx) as? Boolean } ?: false

    private fun ruleTargets(run: Run, rule: TargetRule, ctx: RowCtx): List<UUID> = liveTargets(run, rule, ctx)

    private fun targets(run: Run, v: Val<TargetSet>, ctx: RowCtx): List<UUID> = when (v) {
        is Val.Known -> v.value.entities.map { it.id }
        is Val.Live ->
            if (v.name.startsWith("caster")) listOf(run.request.caster)
            else live.targets(run.cursor.handle, TargetRule.Entity("event.victim"), ctx.binding).entities.map { it.id }
    }

    private fun rowStream(run: Run, row: Row, ctx: RowCtx): SeededStream =
        run.cursor.seed.fork("${row.instance.text}|${row.src.value}|${ctx.base}|${row.tick}")

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

    private class LockRec(val owner: StatePath?, val move: Boolean, val pitch: Boolean, val yaw: Boolean, val untilAbs: Int)

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
        var billboard: DisplayBillboard = DisplayBillboard.FIXED
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

    private class Run(val cursor: CastCursor, val request: CastRequest, val states: Map<StatePath, StateTable>, val listened: Set<EventPath>) {
        val recs = LinkedHashMap<InstanceId, Rec>()
        val pending = ArrayList<RuntimeEvent>()
        val external = ConcurrentLinkedQueue<RuntimeEvent>()
        val firings = HashMap<EventPath, Int>()
        val hitOnce = HashMap<InstanceId, MutableSet<UUID>>()
        val pendingEnds = ArrayList<InstanceId>()
        val locks = ArrayList<LockRec>()

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
