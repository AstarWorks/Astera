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
import com.astarworks.astera.core.domain.skillengine.table.Duration
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
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
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
            val due = when (val d = st.duration) {
                is Duration.Fixed -> lt >= d.ticks
                is Duration.Bounded -> lt >= d.range.max
            }
            val then = st.then
            if (due && then != null) transition(run, lvl, then, null, ExitReason.TIMER, now, null)
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
        val parent = lvl.parent
        if (parent == null) {
            run.finishing = true
            run.pending += RuntimeEvent(EventPath("end"), now, null, null, null)
            return
        }
        val siblings = run.cursor.levels.filter { it.parent === parent }
        if (siblings.all { it.done }) run.pending += RuntimeEvent(EventPath(parent.state.value + ".end"), now, null, null, null)
    }

    private fun exitLevel(run: Run, lvl: LevelCursor, reason: ExitReason, now: Int, binding: EventBinding?) {
        for (child in run.cursor.levels.filter { it.parent === lvl }.asReversed()) exitLevel(run, child, ExitReason.PREEMPT, now, binding)
        pasteSub(run, EventPath("${lvl.state.value}.exit[${reason.name.lowercase()}]"), now, binding, owner = null, direct = true)
        for ((id, rec) in run.recs.entries.toList().asReversed()) if (rec.owner == lvl.state) endInstance(run, id)
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
        val binding = EventBinding(ev.path, ev.index, ev.contact, ev.victim)
        val used = offer(run, ev, binding, now, only = null)
        val sub = run.cursor.skill.table.subTables[ev.path] ?: return
        if (ev.path in used) return
        val owner = run.cursor.levels.firstOrNull { it.state == sub.owner } ?: return
        val lt = now - owner.enteredAt
        if (lt < sub.window.min || lt > sub.window.max) return
        if ((run.firings[ev.path] ?: 0) >= sub.maxFirings) return
        run.firings[ev.path] = (run.firings[ev.path] ?: 0) + 1
        pasteSub(run, ev.path, now, binding, owner = sub.owner, direct = false)
    }

    /** Offers [ev] innermost level first, bubbling to parents; returns the sub-table keys the matched branches pasted. */
    private fun offer(run: Run, ev: RuntimeEvent, binding: EventBinding, now: Int, only: LevelCursor?): Set<EventPath> {
        val used = HashSet<EventPath>()
        val handled = HashSet<LevelCursor>()
        val order = (only?.let { listOf(it) } ?: run.cursor.levels.sortedByDescending { it.depth })
        for (lvl in order) {
            if (lvl.done || lvl in handled || lvl !in run.cursor.levels) continue
            val st = run.states[lvl.state] ?: continue
            val lt = now - lvl.enteredAt
            val br = st.branches.firstOrNull { b ->
                b.event == ev.path && lt in b.window.min..b.window.max &&
                    (b.target == null || lvl.firedTick != now) && guardOk(run, b, binding, lt, now)
            } ?: continue
            handled += lvl
            generateSequence(lvl.parent) { it.parent }.forEach { handled += it }
            br.subTable?.let { used += it }
            transition(run, lvl, br.target, br.subTable, if (ev.path.value == "cancel") ExitReason.CANCEL else ExitReason.EVENT, now, binding)
        }
        return used
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

    private fun emit(run: Run, path: EventPath, now: Int, binding: EventBinding?) {
        run.pending += RuntimeEvent(path, now, binding?.index, binding?.contact, binding?.victim)
    }

    // ------------------------------------------------------------------ cancel

    private fun cancelScope(run: Run, scope: CancelScope, now: Int, ctx: RowCtx?) {
        when (scope) {
            CancelScope.Cast -> {
                val binding = ctx?.binding
                for (lvl in run.cursor.levels.sortedByDescending { it.depth }) {
                    if (lvl !in run.cursor.levels) continue
                    exitLevel(run, lvl, ExitReason.CANCEL, now, binding)
                }
                run.pending += RuntimeEvent(EventPath("cancel"), now, null, null, null)
                run.pending += RuntimeEvent(EventPath("end"), now, null, null, null)
                run.finishing = true
            }
            CancelScope.State -> {
                val lvl = ctx?.level ?: run.cursor.levels.firstOrNull { it.state == ctx?.owner } ?: return
                val ev = RuntimeEvent(EventPath("cancel"), now, null, null, null)
                val st = run.states[lvl.state] ?: return
                val used = offer(run, ev, EventBinding(ev.path, null, null, null), now, only = lvl)
                if (used.isEmpty() && lvl in run.cursor.levels && lvl.firedTick != now) {
                    st.then?.let { transition(run, lvl, it, null, ExitReason.TIMER, now, ctx?.binding) }
                }
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
                    val lvl = ctx.level
                    if (lvl != null && a.state != lvl.state) {
                        if (a.state.value.startsWith(lvl.state.value + "/")) enterLevel(run, a.state, lvl, now)
                        else fault(run, now, src, "Enter(${a.state.value}) is not a child of ${lvl.state.value}")
                    }
                }
                is Action.Exit -> Unit
                is Action.Spawn -> spawn(run, row, a, ctx, now)
                is Action.Key -> key(run, a, ctx, now)
                is Action.Teleport -> teleport(run, a, ctx, now)
                is Action.TrailPoint -> trailPoint(run, a, ctx, now)
                is Action.Particle -> particle(run, row, a, ctx, now)
                is Action.Sound -> sound(run, a, ctx)
                is Action.Hit -> hit(run, row, a, ctx, now)
                is Action.Damage -> damage(run, row, a, ctx, now)
                is Action.Status -> for (v in targets(run, a.targets, ctx)) {
                    gameplay.applyStatus(run.request.caster, v, a.status, int(run, a.stacks, ctx), a.forTicks)
                }
                is Action.Effect -> effect(run, a, ctx, now, src)
                is Action.Lock -> {
                    val until = ctx.base + a.untilTick
                    if (hooks.lock(run.request.caster, a.move, a.pitch, a.yaw, until)) {
                        run.locks += LockRec(ctx.owner, a.move, a.pitch, a.yaw, until)
                    } else {
                        fault(run, now, src, "no lock sink in PlayerHooks (move=${a.move} pitch=${a.pitch} yaw=${a.yaw})")
                    }
                }
                is Action.Call -> {
                    val c = a.callee
                    if (c is Callee.SkillRef && (c.gateCooldown || c.gateCost)) {
                        fault(run, now, src, "gated skill call '${c.id}' needs a cooldown / cost gateway (not in TablePlayer's ports)")
                    }
                }
                is Action.Emit -> emit(run, a.event, now, ctx.binding)
                is Action.End -> if (a.instance !in run.pendingEnds) run.pendingEnds += a.instance
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
        val rec = Rec(a.kind, a.anchor, a.anchorName, now, ctx.owner, local)
        rec.billboard = a.look.fields["billboard"]?.let { runCatching { DisplayBillboard.valueOf(strOf(run, it, ctx).uppercase()) }.getOrNull() } ?: DisplayBillboard.FIXED
        run.recs[id] = rec
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
                particle = a.type, at = Vec3(pos.x, pos.y, pos.z), count = int(run, a.count, ctx), offset = Vec3(off.x, off.y, off.z),
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

    private fun sound(run: Run, a: Action.Sound, ctx: RowCtx) {
        val p = vec(run, a.position, ctx)
        sounds.play(a.sound, Vec3(p.x, p.y, p.z), num(run, a.volume, ctx), num(run, a.pitch, ctx))
    }

    // hit: broad phase from the query, narrow phase from the domain resolver, swept along the frame's last-tick displacement (no tunnelling)
    private fun hit(run: Run, row: Row, a: Action.Hit, ctx: RowCtx, now: Int) {
        val caster = run.request.caster
        val delta = a.frames.firstNotNullOfOrNull { run.recs[it] }?.let { r ->
            val cur = worldOf(run, r, r.localAt(now), ctx.binding, now).pos
            val prev = worldOf(run, r, r.localAt(now - 1), ctx.binding, now - 1).pos
            Vector3d(cur).sub(prev)
        } ?: Vector3d()
        val volumes: (Double) -> ResolvedVolume = when (val s = a.shape) {
            is HitShape.Sphere -> {
                val c = vec(run, s.center, ctx)
                val r = num(run, s.radius, ctx)
                ({ k -> ResolvedVolume.Sphere(Vec3(c.x - delta.x * k, c.y - delta.y * k, c.z - delta.z * k), r) })
            }
            is HitShape.Capsule -> {
                val f = vec(run, s.from, ctx)
                val t = vec(run, s.to, ctx)
                val r = num(run, s.radius, ctx)
                ({ k ->
                    ResolvedVolume.Capsule(
                        Vec3(f.x - delta.x * k, f.y - delta.y * k, f.z - delta.z * k),
                        Vec3(t.x - delta.x * k, t.y - delta.y * k, t.z - delta.z * k), r,
                    )
                })
            }
            is HitShape.Box -> {
                fault(run, now, row.src.value, "Box hit shape has no ResolvedVolume in domain/hit")
                return
            }
        }
        val rule = a.targets
        val allowed = live.targets(run.cursor.handle, rule, ctx.binding).entities.map { it.id }.toSet()
        val steps = if (delta.length() < 1e-9) 1 else min(32, ceil(delta.length() / max(0.25, volumes(0.0).let { if (it is ResolvedVolume.Sphere) it.radius else (it as ResolvedVolume.Capsule).radius })).toInt() + 1)
        val seen = LinkedHashMap<UUID, Pair<Vec3, Vec3>>()
        val once = run.hitOnce.getOrPut(row.instance) { HashSet() }
        val excluded = if (rule is TargetRule.Caster || (rule is TargetRule.Allies && rule.includeSelf)) null else caster
        for (i in steps downTo 0) {
            val k = if (steps == 0) 0.0 else i.toDouble() / steps
            val vol = volumes(k)
            val cands = query.nearby(vol.broadCenter, vol.broadRadius, excluded).filter { it.entityId in allowed && it.entityId !in seen }
            for (ce in resolver.narrow(row.instance.text, now, row.instance.text, vol, Vec3(delta.x, delta.y, delta.z), cands)) {
                if (a.once && ce.victim in once) continue
                seen.putIfAbsent(ce.victim, ce.pointWorld to ce.normalWorld)
            }
        }
        for ((victim, pn) in seen) {
            once += victim
            run.pending += RuntimeEvent(
                EventPath(row.instance.text + ".hit"), now, row.instance.path.lastOrNull()?.index,
                ContactInfo(Vector3d(pn.first.x, pn.first.y, pn.first.z), Vector3d(pn.second.x, pn.second.y, pn.second.z)), EntityRef(victim),
            )
        }
    }

    private fun projectileStep(run: Run, now: Int) {
        for ((id, rec) in run.recs.entries.toList()) {
            if (rec.kind != SpawnKind.PROJECTILE_BODY) continue
            val path = EventPath(id.text + ".block")
            if (path !in run.listened) continue
            val cur = worldOf(run, rec, rec.localAt(now), null, now).pos
            val prev = worldOf(run, rec, rec.localAt(now - 1), null, now - 1).pos
            if (cur.distanceSquared(prev) < 1e-12) continue
            val b = query.raycastBlock(Vec3(prev.x, prev.y, prev.z), Vec3(cur.x, cur.y, cur.z)) ?: continue
            run.pending += RuntimeEvent(
                path, now, id.path.lastOrNull()?.index, ContactInfo(Vector3d(b.point.x, b.point.y, b.point.z), Vector3d(b.normal.x, b.normal.y, b.normal.z)), null,
            )
        }
    }

    private fun damage(run: Run, row: Row, a: Action.Damage, ctx: RowCtx, now: Int) {
        val comps = a.components.map { c -> SkillDamage(c.attribute.name.lowercase(), literalAmount(c)) }
        val chance = num(run, a.chance, ctx)
        val stream = rowStream(run, row, ctx)
        for (v in targets(run, a.targets, ctx)) {
            if (chance < 1.0 && stream.nextDouble() > chance) continue
            val point = ctx.binding?.contact?.point?.let { Vec3(it.x, it.y, it.z) } ?: query.rootOf(v)?.feet ?: Vec3.ZERO
            gameplay.damage(run.cursor.skill.def.id, run.request.caster, v, comps, point, false, false)
        }
    }

    private fun literalAmount(c: DamageComponent): Double {
        require(c.base is NumericValue.Literal && c.multiplier is NumericValue.Literal) { "damage component is not a compile-time literal" }
        return c.constantValue
    }

    private fun effect(run: Run, a: Action.Effect, ctx: RowCtx, now: Int, src: String) {
        val p = a.payload
        val victims = p["targets"]?.let { v ->
            @Suppress("UNCHECKED_CAST")
            targets(run, v as Val<TargetSet>, ctx)
        } ?: listOf(run.request.caster)
        fun d(key: String): Double = dbl(run, p[key] ?: throw IllegalArgumentException("effect '${a.op.value}' needs '$key'"), ctx)
        fun s(key: String): String = strOf(run, p[key] ?: throw IllegalArgumentException("effect '${a.op.value}' needs '$key'"), ctx)
        fun v(key: String): Vector3d = any(run, p[key] ?: throw IllegalArgumentException("effect '${a.op.value}' needs '$key'"), ctx) as Vector3d
        when (a.op.value) {
            "impulse", "knockback" -> {
                val vv = v("vector")
                victims.forEach { gameplay.impulse(it, Vec3(vv.x, vv.y, vv.z)) }
            }
            "resource" -> victims.forEach { gameplay.restoreResource(it, s("resource"), d("amount")) }
            "heal" -> victims.forEach { gameplay.restoreResource(it, "health", d("amount")) }
            "modify_attribute" -> {
                val dur = p["for"]?.let { int(run, it as Val<Int>, ctx) }
                victims.forEach { gameplay.modifyAttribute(run.request.caster, it, s("attribute"), s("operation"), d("amount"), dur) }
            }
            else -> fault(run, now, src, "effect op '${a.op.value}' has no gateway in TablePlayer's ports (dash / reel / rig / emit need a movement gateway)")
        }
    }

    // ------------------------------------------------------------------ Val.Live resolution (C4c)

    private fun vec(run: Run, v: Vec, ctx: RowCtx): Vector3d = when (v) {
        is Val.Known -> Vector3d(v.value)
        is Val.Live -> live.vec(run.cursor.handle, v.name, ctx.binding)
    }

    private fun quat(run: Run, v: Rot, ctx: RowCtx): Quaterniond = when (v) {
        is Val.Known -> Quaterniond(v.value)
        is Val.Live -> live.rot(run.cursor.handle, v.name, ctx.binding)
    }

    /** A live scalar is the `x` of the named live vector (LiveValueSource has no scalar lookup; see the report). */
    private fun num(run: Run, v: Num, ctx: RowCtx): Double = when (v) {
        is Val.Known -> v.value
        is Val.Live -> live.vec(run.cursor.handle, v.name, ctx.binding).x
    }

    private fun int(run: Run, v: Val<Int>, ctx: RowCtx): Int = when (v) {
        is Val.Known -> v.value
        is Val.Live -> live.vec(run.cursor.handle, v.name, ctx.binding).x.toInt()
    }

    private fun any(run: Run, v: Val<Any>, ctx: RowCtx): Any = when (v) {
        is Val.Known -> v.value
        is Val.Live -> live.vec(run.cursor.handle, v.name, ctx.binding)
    }

    private fun dbl(run: Run, v: Val<Any>, ctx: RowCtx): Double = (any(run, v, ctx) as Number).toDouble()

    private fun strOf(run: Run, v: Val<Any>, ctx: RowCtx): String = any(run, v, ctx).toString()

    private fun req(run: Run, look: Look, key: String, ctx: RowCtx): String =
        strOf(run, look.fields[key] ?: throw IllegalArgumentException("look needs '$key'"), ctx)

    private fun flag(run: Run, look: Look, key: String, ctx: RowCtx): Boolean = look.fields[key]?.let { any(run, it, ctx) as? Boolean } ?: false

    private fun targets(run: Run, v: Val<TargetSet>, ctx: RowCtx): List<UUID> = when (v) {
        is Val.Known -> v.value.entities.map { it.id }
        is Val.Live ->
            if (v.name.startsWith("caster")) listOf(run.request.caster)
            else live.targets(run.cursor.handle, TargetRule.Victim, ctx.binding).entities.map { it.id }
    }

    private fun rowStream(run: Run, row: Row, ctx: RowCtx): SeededStream =
        run.cursor.seed.fork("${row.instance.text}|${row.src.value}|${ctx.base}|${row.tick}")

    private fun fault(run: Run, tick: Int, src: String, message: String) {
        faultLog += PlayerFault(run.cursor.handle, tick, src, message)
    }

    // ------------------------------------------------------------------ state

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
        var inTick = false
        var finishing = false
        var finalized = false
    }
}
