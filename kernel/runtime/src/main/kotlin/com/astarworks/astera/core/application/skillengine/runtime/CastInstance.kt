package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.skillengine.frame.Axes
import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.application.skillengine.runtime.ops.MovementHost
import com.astarworks.astera.core.application.skillengine.runtime.ops.VictimBind
import com.astarworks.astera.core.application.skillengine.runtime.ops.OpTargetResolver
import com.astarworks.astera.core.application.skillengine.runtime.ops.SkillCastScope
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationCastInfo
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprHost
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.hit.ContactResolver
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
import com.astarworks.astera.core.domain.skillengine.model.FrameDecl
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.SkillEvents
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.motion.MotionComposer
import com.astarworks.astera.core.domain.skillengine.motion.MotionPhase
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.ControlOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import java.util.UUID
import kotlin.random.Random

private val NIL_VICTIM: UUID = UUID(0L, 0L)
private val UP = Vec3(0.0, 1.0, 0.0)

/**
 * One running cast (ADR-0301) and the single per-cast object every collaborator talks to: it is the executors'
 * [OpContext] / [OpHost], the spawn / damage executors' [SkillCastScope], the movement executors' [MovementHost],
 * the status executors' [OpTargetResolver] and the presentation runtime's [PresentationCastInfo]. It owns the frame
 * tree, the motion composer, hit volumes, scheduled events and the two timelines. Casts in progress keep their
 * [CompiledSkill], so a per-skill reload only affects casts started afterwards.
 *
 * Tick order (design 3.7): external + scheduled events -> timeline rows (gameplay, presentation) -> motion
 * laws -> hit volumes -> after each phase the raised events drain (gameplay handlers then presentation handlers
 * per event) -> presentation tick -> snapshot / observer.
 */
internal class CastInstance(
    val id: CastId,
    val request: CastRequest,
    private val deps: CastRunnerDeps,
    private val bank: ExprBank,
    private val chain: List<String>,
    private val startChild: (CastRequest, List<String>) -> Unit,
    private val diagnose: (CastDiagnostic) -> Unit,
) : OpContext, OpHost, SkillCastScope, MovementHost, OpTargetResolver, PresentationCastInfo {

    private val hitCounts = HashMap<String, Int>()

    private class HitReg(val reg: HitRegistration, var prevAnchor: Vec3? = null, var passesLeft: Int? = reg.ticks)
    private class Scheduled(val dueTick: Int, val event: String?, val source: String?, val ops: List<SkillOp>?, val victim: UUID?, val bind: VictimBind? = null)
    private class OpenWindow(val op: ControlOp.Window, val untilTick: Int)
    private class Listener(val event: String, val source: String, val handler: () -> Unit)

    val skill: CompiledSkill = request.skill
    private val def = skill.def

    override val cast: CastId get() = id
    override val world: com.astarworks.astera.core.domain.world.WorldId get() = request.world
    override val tree: FrameTree = deps.newTree()
    override val ports: com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts = deps.portsIn(request.world)
    override val host: OpHost get() = this
    val bus = InMemoryCastEventBus()

    // --- params after modifiers (snapshotted at start, F-12) ---
    val params: Map<String, ParamValue>
    val exposed: Map<String, Double>

    init {
        val all = LinkedHashMap<String, ParamValue>()
        for ((k, v) in def.gameplay.params + def.presentation.params) {
            val ex = def.gameplay.expose.firstOrNull { it.param == k }
            all[k] = if (v is ParamValue.Num && ex != null) ParamValue.Num(request.modifiers.apply(ex, v.value)) else v
        }
        params = all
        val e = LinkedHashMap<String, Double>()
        for (ex in def.gameplay.expose) (all[ex.param] as? ParamValue.Num)?.let { e[ex.name] = it.value }
        exposed = e
    }

    override val values: ValueResolver = CastValueResolver(this, bank)

    // --- state ---
    override var tick: Int = request.startTick
        private set
    val ticksSinceStart: Int get() = tick - request.startTick
    var currentEvent: CastEvent? = null
        private set
    var currentOpId: String? = null
        private set
    var ended: Boolean = false
        private set

    private val nodeNames = LinkedHashSet<String>()
    private val motion = MotionComposer(tree, { exprEnv() }, ::isFrozen)
    private val lawSources = HashMap<FrameId, String?>()
    private val hitRegs = ArrayList<HitReg>()
    private val hitSeen = HashMap<String, Int>()
    private val contactedOps = HashSet<String>()
    private val blockedOps = HashSet<String>()
    private val scheduled = ArrayList<Scheduled>()
    private val listeners = ArrayList<Listener>()
    private val windows = ArrayList<OpenWindow>()
    private var incomingAmount = 0.0
    private val frozenUntil = HashMap<String, Int>()
    private val queue = ArrayDeque<CastEvent>()
    private var stormReported = false
    private val tickEvents = ArrayList<CastEvent>()
    private val victims = LinkedHashSet<UUID>()
    private val counters = HashMap<String, Double>()
    private val random = Random(request.seed)
    private var lastVictim: UUID? = null
    private var victimOverride: UUID? = null
    private var bindOverride: VictimBind? = null
    private var contactOverride: ContactEvent? = null
    private var contactSeq = 0
    private var snapshot: Map<String, Vec3> = emptyMap()
    private var lastVel: Map<String, Vec3> = emptyMap()
    private var casterYaw = request.casterYawDeg
    private var casterPitch = request.casterPitchDeg
    private var casterEye = 1.62

    private val gameplayTimeline = Timeline(def.gameplay.timeline)
    private val presentationTimeline = Timeline(def.presentation.timeline)

    // ------------------------------------------------------------------ start

    fun setup(): CastError? {
        val world = FrameNode(FrameId("world"), FrameKind.WORLD, null, Frame(Vec3.ZERO))
        addNode(world)?.let { return it }
        addNode(FrameNode(FrameId("caster"), FrameKind.CASTER, FrameId("world"), casterLocal()))?.let { return it }
        addNode(FrameNode(FrameId("aim"), FrameKind.CASTER, FrameId("world"), aimLocal()))?.let { return it }
        addNode(FrameNode(FrameId("enemy"), FrameKind.ENEMY, FrameId("world"), Frame(Vec3.ZERO)))?.let { return it }
        for ((name, off) in CASTER_POINTS) tree.definePoint(FramePoint(FrameId("caster"), name, off))
        tree.definePoint(FramePoint(FrameId("caster"), "eye", Vec3(0.0, casterEye, 0.0)))

        val models = HashMap<String, String?>()
        var pending = def.frames.toList()
        while (pending.isNotEmpty()) {
            val next = ArrayList<FrameDecl>()
            var progressed = false
            for (d in pending) {
                if (d.parent.frame !in nodeNames || d.orient?.let { it.look !in nodeNames || it.reference !in nodeNames } == true) {
                    next += d
                    continue
                }
                addDecl(d, models)?.let { return it }
                progressed = true
            }
            if (!progressed) {
                return CastError.FrameSetup("frames ${next.map { it.id }}: parent frame not declared or cyclic (${next.map { it.parent }})")
            }
            pending = next
        }
        takeSnapshot()
        queue += CastEvent(SkillEvents.START, tick, source = null)
        drain()
        return null
    }

    private fun addDecl(d: FrameDecl, models: MutableMap<String, String?>): CastError? {
        val parentOffset = d.parent.point?.let { p ->
            tree.point(FrameId(d.parent.frame), p)?.offset
                ?: return CastError.FrameSetup("frame '${d.id}': parent point '${d.parent}' is not defined")
        } ?: Vec3.ZERO
        val local = Frame(parentOffset + (d.offset ?: Vec3.ZERO))
        addNode(FrameNode(FrameId(d.id), d.kind, FrameId(d.parent.frame), local, d.orient))?.let { return it }
        val model = d.model ?: models[d.parent.frame]
        models[d.id] = model
        if ((d.kind == FrameKind.ITEM || d.kind == FrameKind.PART) && model != null) {
            for (p in deps.points.points(model, if (d.kind == FrameKind.PART) d.part else null)) {
                tree.definePoint(FramePoint(FrameId(d.id), p.name, p.offset, p.axis))
            }
        }
        return null
    }

    private fun addNode(node: FrameNode): CastError? = when (val r = tree.add(node)) {
        is Result.Success -> {
            nodeNames += node.id.value
            null
        }
        is Result.Failure -> CastError.FrameSetup("cannot add frame '${node.id.value}': ${r.error}")
    }

    /** The `aim` frame: origin at the eye, rotation = the full look (yaw and pitch); local +Z is forward, +Y the look-perpendicular up. */
    private fun aimLocal(): Frame = Frame.fromYawPitch(request.casterFeet + Vec3(0.0, casterEye, 0.0), casterYaw, casterPitch)

    private fun casterLocal(): Frame = Frame.fromYawPitch(request.casterFeet, casterYaw, 0.0)

    // ------------------------------------------------------------------- tick

    var lastTick: Int = request.startTick
        private set

    fun tick(currentTick: Int) {
        if (ended) return
        lastTick = currentTick
        if (currentTick < request.startTick) return
        tick = currentTick
        tickEvents.clear()
        windows.removeAll { it.untilTick <= tick }
        refreshRoots()
        interruptingStatus()?.let { status ->
            // The caster was stunned or silenced mid-cast: every handle, dash and lock the cast owns is released, END fires with the status as its source.
            finishNow(cancelled = true, reason = status)
            takeSnapshot()
            deps.observer?.onTick(id, tick, tree, nodeNames.toSet(), tickEvents.toList())
            return
        }

        // external + scheduled events
        val due = scheduled.filter { it.dueTick <= tick }
        scheduled.removeAll(due.toSet())
        for (s in due) {
            if (s.ops != null) runScheduledOps(s) else queue += CastEvent(s.event!!, tick, s.source, victim = s.victim)
        }
        drain()

        // gameplay timeline rows
        for (row in gameplayTimeline.due(ticksSinceStart, ::eventSince, bus::hasRaised)) runRow(row.whenExpr, row.ops)
        drain()

        stepMotion()
        drain()

        resolveHits()
        drain()

        // presentation rows run after the tick's motion and hits: a trail emits at the post-move position and not at all
        // on the tick its projectile ended (v1 emitted from inside the projectile step).
        for (row in presentationTimeline.due(ticksSinceStart, ::eventSince, bus::hasRaised)) runRow(row.whenExpr, row.ops)
        drain()

        deps.presentation.tickCast(id)

        if (ticksSinceStart >= deps.maxCastTicks || idle()) finishNow()
        takeSnapshot()
        deps.observer?.onTick(id, tick, tree, nodeNames.toSet(), tickEvents.toList())
    }

    /** The first status on the caster that forbids casting (see [INTERRUPTING_STATUSES]), or null. */
    private fun interruptingStatus(): String? =
        INTERRUPTING_STATUSES.firstOrNull { ports.gameplay.statusStacks(request.caster, it) > 0 }

    private fun eventSince(name: String): Int? = bus.firstRaisedAt(name)?.minus(request.startTick)

    private fun runRow(guard: com.astarworks.astera.core.domain.skillengine.model.Value<Boolean>?, ops: List<SkillOp>) {
        currentOpId = null
        if (guard != null) {
            val ok = try {
                values.bool(guard)
            } catch (e: ValueResolveException) {
                report(null, "row guard: ${e.message}")
                false
            }
            if (!ok) return
        }
        run(ops)
    }

    private fun runScheduledOps(s: Scheduled) {
        currentOpId = s.source
        val victim = s.victim
        if (victim != null) withBind(s.bind ?: VictimBind(victim, world)) { run(s.ops!!) } else run(s.ops!!)
        currentOpId = null
    }

    private fun idle(): Boolean =
        !motion.hasWork() && scheduled.isEmpty() && windows.isEmpty() && queue.isEmpty() && !deps.dashes.isDashing(id) &&
            !gameplayTimeline.pending(::eventSince) && !presentationTimeline.pending(::eventSince)

    private fun finishNow(cancelled: Boolean = false, reason: String? = null) {
        if (ended) return
        // END goes first so its handlers run even when a backlog of deferred events is pending.
        queue.addFirst(CastEvent(SkillEvents.END, tick, source = reason))
        try {
            drain()
            if (queue.isNotEmpty()) report(null, "${queue.size} queued events were not delivered before the cast ended (next: ${queue.first().name})")
        } finally {
            // Whatever the END handlers did, every handle, dash and lock this cast owns is released.
            ended = true
            deps.presentation.endCast(id, cancelled)
            deps.dashes.cancel(id)
            deps.locks.cancel(id)
            hitRegs.clear()
            listeners.clear()
            windows.clear()
        }
    }

    fun cancel() {
        if (ended) return
        if (tick < request.startTick) tick = request.startTick
        finishNow(cancelled = true)
        takeSnapshot()
        deps.observer?.onTick(id, tick, tree, nodeNames.toSet(), tickEvents.toList())
    }

    fun enqueueExternal(event: CastEvent) {
        if (!ended) queue += event.copy(tick = maxOf(event.tick, lastTick))
    }

    // ---------------------------------------------------------------- events

    /**
     * Delivers queued events. At most [MAX_EVENTS_PER_DRAIN] per phase so a handler that raises its own event cannot
     * stall the server tick; the rest STAYS queued (nothing is dropped) and the next phase delivers it. The overflow is
     * reported once per cast, and the cast cannot end while events are pending ([idle]), so a runaway loop is bounded by
     * `maxCastTicks` and visible in the diagnostics.
     */
    private fun drain() {
        var handled = 0
        while (queue.isNotEmpty()) {
            if (handled++ >= MAX_EVENTS_PER_DRAIN) {
                if (!stormReported) {
                    stormReported = true
                    report(
                        null,
                        "event storm: more than $MAX_EVENTS_PER_DRAIN events in one phase; ${queue.size} deferred to the next phase " +
                            "(next: ${queue.first().name} from ${queue.first().source})",
                    )
                }
                return
            }
            handle(queue.removeFirst())
        }
    }

    private fun handle(e: CastEvent) {
        currentEvent = e
        tickEvents += e
        try {
            prepareContact(e)
            val bound = e.victim?.takeIf { e.contact == null }
            if (bound != null && bound != lastVictim) {
                victims += bound
                lastVictim = bound
                refreshEnemy(bound)
            }
            bus.raise(e)
            motion.raise(e.name)
            val v = e.victim
            if (v != null) withVictim(v) { runHandlers(e) } else runHandlers(e)
            for (l in listeners.toList()) {
                if (l.event != e.name || l.source != e.source) continue
                try {
                    l.handler()
                } catch (ex: Exception) {
                    report(l.source, "${e.name} handler: ${ex::class.simpleName}: ${ex.message}")
                }
            }
        } finally {
            currentEvent = null
        }
    }

    private fun runHandlers(e: CastEvent) {
        def.gameplay.on[e.name]?.let { run(it) }
        def.presentation.on[e.name]?.let { run(it) }
    }

    override fun raise(event: String) {
        queue += CastEvent(event, tick, source = currentOpId, victim = currentVictim)
    }

    fun hasRaised(name: String): Boolean = bus.hasRaised(name)

    // --------------------------------------------------------------- op runs

    override fun run(ops: List<SkillOp>) {
        for (op in ops) runOne(op)
    }

    private fun runOne(op: SkillOp) {
        val outer = currentOpId
        currentOpId = op.id
        try {
            val guard = op.whenExpr
            if (guard != null && !values.bool(guard)) return
            val executor = deps.executors.find(op)
            if (executor == null) {
                report(op.id, "no executor registered for ${op::class.qualifiedName}")
                return
            }
            executor.execute(op, this)
        } catch (e: ValueResolveException) {
            report(op.id, e.message ?: "value error")
        } catch (e: Exception) {
            report(op.id, "${e::class.simpleName}: ${e.message}")
        } finally {
            currentOpId = outer
        }
    }

    private fun report(opId: String?, message: String) {
        diagnose(CastDiagnostic(id, tick, opId, message))
    }

    // ------------------------------------------------------------------ OpHost

    override val caster: UUID get() = request.caster
    override val skillId: String get() = def.id.substringBefore('@')
    override val currentVictim: UUID?
        get() {
            victimOverride?.let { return it }
            val v = activeContact()?.victim
            return if (v != null && v != NIL_VICTIM) v else lastVictim
        }
    override val contactNormal: Vec3? get() = activeContact()?.normalWorld
    override val cooldowns: ISkillCooldownGateway get() = deps.cooldowns
    override val items: ISkillItemGateway get() = deps.items

    override fun registerHit(registration: HitRegistration) {
        hitRegs.removeAll { it.reg.id == registration.id && it.reg.frame == registration.frame }
        // Seed the sweep origin so the very first segment (muzzle -> first position) is block-tested and swept too (v1 probed from the spawn point).
        val seed = try { resolveVolume(registration)?.let { anchorOf(it) } } catch (e: ValueResolveException) { null }
        hitRegs += HitReg(registration, seed)
    }

    override fun startMotion(frame: FrameId, spec: MotionSpec) {
        val law = deps.motion.create(frame, spec, values)
        if (law.phase == MotionPhase.TRANSLATE) lawSources[frame] = currentOpId
        motion.start(law, tick)
    }

    override fun schedule(afterTicks: Int, event: String, source: String) {
        scheduled += Scheduled(tick + maxOf(0, afterTicks), event, source, null, currentVictim)
    }

    override fun openWindow(op: ControlOp.Window, ticks: Int) {
        windows += OpenWindow(op, tick + ticks)
    }

    /**
     * A hit of [amount] from [attacker] reaches the caster between ticks. Returns the damage multiplier the open
     * windows impose (1 = untouched, 0 = negated); a passing window queues its body for the next tick with
     * [attacker] bound as the victim.
     */
    fun interceptIncoming(attacker: UUID, amount: Double): Double {
        if (ended || windows.isEmpty()) return 1.0
        var multiplier = 1.0
        for (w in windows.toList()) {
            if (w.untilTick <= lastTick) continue
            val guard = w.op.filter
            incomingAmount = amount
            val pass = try {
                guard == null || withVictimResult(attacker) { values.bool(guard) }
            } catch (e: ValueResolveException) {
                report(w.op.id, "window filter: ${e.message}")
                false
            } finally {
                incomingAmount = 0.0
            }
            if (!pass) continue
            multiplier *= w.op.scale
            if (w.op.body.isNotEmpty()) scheduled += Scheduled(lastTick + 1, null, w.op.id, w.op.body, attacker)
            if (w.op.once) windows.remove(w)
        }
        return multiplier
    }

    private fun <T> withVictimResult(victim: UUID, block: () -> T): T {
        val outer = victimOverride
        victimOverride = victim
        try {
            return block()
        } finally {
            victimOverride = outer
        }
    }

    override fun scheduleOps(afterTicks: Int, ops: List<SkillOp>) {
        scheduled += Scheduled(tick + maxOf(0, afterTicks), null, currentOpId, ops, currentVictim, currentBind())
    }

    override fun freezeFrames(frames: List<String>, ticks: Int) {
        for (f in frames) frozenUntil[f] = tick + ticks
    }

    override fun castSkill(skillId: String) {
        val child = deps.skills(skillId)
        if (child == null) {
            fail(currentOpId ?: "cast_skill", "unknown skill '$skillId'")
            return
        }
        val path = chain + def.id
        if (skillId in path || path.size >= MAX_CHAIN) {
            fail(currentOpId ?: "cast_skill", "cast_skill '$skillId' would ${if (skillId in path) "cycle" else "exceed depth $MAX_CHAIN"} (${path.joinToString(" -> ")})")
            return
        }
        val feet = ports.entities.rootOf(request.caster)?.feet ?: request.casterFeet
        startChild(
            request.copy(skill = child, casterFeet = feet, casterYawDeg = casterYaw, casterPitchDeg = casterPitch, seed = random.nextLong(), startTick = tick),
            path,
        )
    }

    override fun addCounter(key: String, delta: Double) {
        counters[key] = (counters[key] ?: 0.0) + delta
    }

    override fun nextRandom(): Double = random.nextDouble()

    override fun resolveTargets(ref: TargetRef): List<UUID> = when (ref) {
        TargetRef.Victim -> listOfNotNull(currentVictim)
        TargetRef.Caster -> listOf(caster)
        is TargetRef.Enemies -> around(ref.around, ref.radius, caster, ref.maxCount) { !isAlly(it) }
        is TargetRef.Allies -> {
            val mates = around(ref.around, ref.radius, caster, ref.maxCount) { it != caster && isAlly(it) }
            if (ref.includeSelf) listOf(caster) + mates else mates
        }
    }

    private fun around(at: PointRef, radius: com.astarworks.astera.core.domain.skillengine.model.Value<Double>, excluding: UUID?, maxCount: Int?, keep: (UUID) -> Boolean): List<UUID> {
        val p = tree.pointWorld(FrameId(at.frame), at.point).getOrNull() ?: return emptyList()
        val found = ports.entities.nearby(p, values.num(radius), excluding).filter { keep(it.entityId) }
        return (if (maxCount == null) found else found.sortedBy { it.feet.distanceSquared(p) }.take(maxCount)).map { it.entityId }
    }

    override fun withVictim(victim: UUID, block: () -> Unit) = withBind(VictimBind(victim, world), block)

    /** Runs [block] under [bind]; delayed ops queued inside re-enter under the same [bind] (v1 boundTargetState survives the delay). */
    private fun withBind(bind: VictimBind, block: () -> Unit) {
        val outer = victimOverride
        val outerBind = bindOverride
        victimOverride = bind.victim
        bindOverride = bind
        try {
            block()
        } finally {
            victimOverride = outer
            bindOverride = outerBind
        }
    }

    private fun currentBind(): VictimBind? = bindOverride?.takeIf { it.victim == victimOverride }

    override fun victimBind(ctx: OpContext): VictimBind? = currentBind()

    private var copyIndex = 0
    private var copyCount = 1

    override fun withIndex(index: Int, count: Int, block: () -> Unit) {
        val (oi, on) = copyIndex to copyCount
        copyIndex = index; copyCount = count
        try {
            block()
        } finally {
            copyIndex = oi; copyCount = on
        }
    }

    private var copySample = 0

    override fun withSample(sample: Int, block: () -> Unit) {
        val outer = copySample
        copySample = sample
        try {
            block()
        } finally {
            copySample = outer
        }
    }

    override fun fail(opId: String, reason: String) {
        report(opId, reason)
    }

    override fun failHere(reason: String) {
        report(currentOpId, reason)
    }

    // ----------------------------------------------------------- SkillCastScope

    override val contact: ContactEvent? get() = activeContact()
    override val contacts: ContactResolver get() = deps.contacts
    override val effects: ISkillWorldEffectGateway get() = deps.effects

    override fun isAlly(entity: UUID): Boolean = deps.isAlly(request.caster, entity)

    override fun <T> withContact(contact: ContactEvent, block: () -> T): T {
        val outer = contactOverride
        contactOverride = contact
        try {
            return block()
        } finally {
            contactOverride = outer
        }
    }

    override fun onEvent(event: String, source: String, handler: () -> Unit) {
        // Re-arming the same (event, source) REPLACES the handler: a re-spawned projectile must not run its on_hit twice.
        listeners.removeAll { it.event == event && it.source == source }
        listeners += Listener(event, source, handler)
    }

    override fun endProjectile(source: String, body: FrameId, keepBody: Boolean) {
        hitRegs.removeAll { it.reg.id == source }
        if (!keepBody) deps.presentation.endBody(id, body.value)
        motion.stop(body)
        lawSources.remove(body)
    }

    override fun removeBody(body: FrameId) {
        deps.presentation.endBody(id, body.value)
    }

    override fun ignoreBlocks(source: String) {
        blockedOps += source
    }

    override fun bounceProjectile(source: String, body: FrameId, spec: MotionSpec.Fly, nudge: Vec3) {
        motion.stop(body)
        val node = tree.node(body)
        if (node != null) tree.setLocal(body, node.local.copy(origin = node.local.origin + nudge))
        blockedOps -= source
        val anchor = tree.pointWorld(body, null).getOrNull()
        for (r in hitRegs) if (r.reg.id == source) r.prevAnchor = anchor
        lawSources[body] = source
        motion.start(deps.motion.create(body, spec, values), tick)
    }

    private fun activeContact(): ContactEvent? = contactOverride ?: currentEvent?.contact

    // ------------------------------------------------- MovementHost / targets

    override fun caster(ctx: OpContext): UUID = request.caster

    override fun targets(ctx: OpContext, target: TargetRef): List<UUID> = resolveTargets(target)

    override fun resolve(ctx: OpContext, target: TargetRef): List<UUID> = resolveTargets(target)

    override fun location(ctx: OpContext, spec: LocationSpec): Vec3? = when (spec) {
        is LocationSpec.AtPoint -> tree.pointWorld(FrameId(spec.point.frame), spec.point.point).getOrNull()
            ?: run { failHere("location '${spec.point}' does not resolve"); null }
        is LocationSpec.AtValue -> values.vec3(spec.value)
    }

    /** [point] in the `enemy@victim` frame (the parent of the `contact` frame): feet origin, yawed like [refreshEnemy]. */
    private fun localToVictim(victim: UUID, point: Vec3): Vec3 {
        val root = ports.entities.rootOf(victim) ?: return point
        val pose = Frame.fromYawPitch(root.feet, root.yawDeg, 0.0)
        return pose.rotation.conjugate().rotate(point - pose.origin)
    }

    override fun runWithVictim(ctx: OpContext, source: String, victim: UUID, point: Vec3, ops: List<SkillOp>) {
        val synthetic = ContactEvent(
            hitId = source, tick = tick, victim = victim, hitterFrame = "caster", part = null,
            pointWorld = point, normalWorld = UP, pointLocal = localToVictim(victim, point), normalLocal = UP, relVelocity = Vec3.ZERO,
        )
        withContact(synthetic) { withVictim(victim) { run(ops) } }
        queue += CastEvent("dash_hit", tick, source = source, contact = synthetic)
    }

    // ------------------------------------------------- PresentationCastInfo

    override fun owner(cast: CastId): UUID = request.caster

    override fun targets(cast: CastId, target: TargetRef): List<UUID> = resolveTargets(target)

    override fun hasRaised(cast: CastId, event: String): Boolean = bus.hasRaised(event)

    // ---------------------------------------------------------------- motion

    private fun isFrozen(frame: FrameId): Boolean {
        if (frozenUntil.isEmpty()) return false
        var f: FrameId? = frame
        while (f != null) {
            if ((frozenUntil[f.value] ?: -1) >= tick) return true
            f = tree.node(f)?.parent
        }
        return false
    }

    private fun stepMotion() {
        for (m in try {
            motion.tick(tick)
        } catch (e: Exception) {
            report(null, "motion: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }) {
            queue += CastEvent(m.event, tick, source = lawSources[m.frame] ?: m.frame.value)
        }
    }

    // ------------------------------------------------------------------ hits

    private fun resolveHits() {
        if (hitRegs.isEmpty()) return
        for (reg in hitRegs.toList()) {
            // A bounded registration (v1 one-shot selection) gets exactly `ticks` resolution passes, frozen or not.
            reg.passesLeft?.let { left ->
                reg.passesLeft = left - 1
                if (left <= 1) hitRegs.remove(reg)
            }
            if (isFrozen(FrameId(reg.reg.frame))) continue
            val rawVolume = try {
                resolveVolume(reg.reg)
            } catch (e: ValueResolveException) {
                report(reg.reg.id, "hit volume: ${e.message}")
                null
            } ?: continue
            val hitterVel = hitterVelocity(reg.reg.frame)
            val volume = sweep(reg, rawVolume)
            val candidates = ports.entities.nearby(volume.broadCenter, volume.broadRadius, request.caster)
            val anchor = anchorOf(volume)
            val prev = reg.prevAnchor
            reg.prevAnchor = anchor
            // v1: the wall is probed first and an entity only counts if it is nearer than the wall along the segment.
            val wall = if (prev != null && anchor != null && prev != anchor && reg.reg.id !in blockedOps) {
                ports.entities.raycastBlock(prev, anchor)
            } else {
                null
            }
            val narrowed = deps.contacts.narrow(reg.reg.id, tick, reg.reg.frame, volume, hitterVel, candidates)
            val found = if (wall != null && prev != null) {
                val limit = prev.distance(wall.point)
                narrowed.filter { prev.distance(it.pointWorld) <= limit }
            } else if (volume is ResolvedVolume.Sector && reg.reg.id !in blockedOps) {
                // A sector has no travelled segment; a wall between its apex and the victim stops the swing (no block event: it is a melee arc).
                narrowed.filter { lineClear(volume.origin, it.pointWorld) }
            } else {
                narrowed
            }
            var contacted = false
            val cap = reg.reg.maxCount
            val nearestFirst = if (cap == null) found else found.sortedBy { it.pointWorld.distanceSquared(volume.broadCenter) }
            for (c in nearestFirst) {
                if (cap != null && hitCounts.getOrDefault(reg.reg.id, 0) >= cap) break
                if (!windowAccepts(reg.reg, c.victim)) continue
                if (cap != null) hitCounts.merge(reg.reg.id, 1, Int::plus)
                contacted = true
                contactedOps += reg.reg.id
                queue += CastEvent(SkillEvents.HIT, tick, source = reg.reg.id, contact = c)
            }
            if (!contacted && wall != null) {
                blockedOps += reg.reg.id
                snapBody(reg.reg.frame, wall.point)
                queue += CastEvent(SkillEvents.BLOCK, tick, source = reg.reg.id, contact = blockContact(reg.reg, wall.point, wall.normal, wall.blockKey, hitterVel))
            }
        }
    }

    /** No solid block between [from] and [to] (the end is pulled back a hair so a point on a surface is not its own wall). */
    private fun lineClear(from: Vec3, to: Vec3): Boolean {
        val d = from - to
        val len = d.length()
        val end = if (len > 0.1) to + d * (0.05 / len) else to
        return ports.entities.raycastBlock(from, end) == null
    }

    /** v1 `flight.position = contact.at`: the body stops at the face instead of overshooting into the wall. */
    private fun snapBody(frame: String, at: Vec3) {
        val id = FrameId(frame)
        val node = tree.node(id) ?: return
        val world = tree.worldOf(id).getOrNull() ?: return
        tree.setLocal(id, node.local.copy(origin = node.local.origin + (at - world.origin)))
    }

    private fun blockContact(reg: HitRegistration, point: Vec3, normal: Vec3, key: String, vel: Vec3) = ContactEvent(
        hitId = reg.id, tick = tick, victim = NIL_VICTIM, hitterFrame = reg.frame, part = key,
        pointWorld = point, normalWorld = normal, pointLocal = point, normalLocal = normal, relVelocity = vel,
    )

    private fun windowAccepts(reg: HitRegistration, victim: UUID): Boolean = when (val w = reg.window) {
        HitWindow.Once -> hitSeen.putIfAbsent("${reg.id}|$victim", tick) == null
        HitWindow.PerPart -> hitSeen.putIfAbsent("${reg.id}|${reg.frame}|$victim", tick) == null
        is HitWindow.EveryTicks -> {
            val key = "${reg.id}|${reg.frame}|$victim"
            val last = hitSeen[key]
            if (last == null || tick - last >= w.ticks) {
                hitSeen[key] = tick
                true
            } else {
                false
            }
        }
    }

    /** A `swept` sphere becomes the capsule from last tick's centre to this tick's (no stepping over thin targets). */
    private fun sweep(reg: HitReg, v: ResolvedVolume): ResolvedVolume {
        val prev = reg.prevAnchor
        if (!reg.reg.swept || v !is ResolvedVolume.Sphere || prev == null || prev == v.center) return v
        if (prev.distance(v.center) > MAX_SWEEP) return v
        return ResolvedVolume.Capsule(prev, v.center, v.radius)
    }

    private fun anchorOf(v: ResolvedVolume): Vec3? = when (v) {
        is ResolvedVolume.Capsule -> v.b
        is ResolvedVolume.Sphere -> v.center
        is ResolvedVolume.Sector -> null
    }

    private fun hitterVelocity(frame: String): Vec3 {
        val now = tree.pointWorld(FrameId(frame), null).getOrNull() ?: return Vec3.ZERO
        val before = snapshot[frame] ?: return Vec3.ZERO
        return now - before
    }

    private fun resolveVolume(reg: HitRegistration): ResolvedVolume? {
        val host = reg.frame
        fun pt(ref: PointRef): Vec3? =
            tree.pointWorld(FrameId(if (ref.frame == "self") host else ref.frame), ref.point).getOrNull()
        fun axis(name: String): Vec3? = axisOf(if (name.startsWith("self.")) host + name.removePrefix("self") else name)
        return when (val v = reg.volume) {
            is HitVolume.Capsule -> {
                val a = pt(v.from)
                val b = pt(v.to)
                if (a == null || b == null) {
                    report(reg.id, "capsule points ${v.from}/${v.to} do not resolve on '$host'")
                    return null
                }
                ResolvedVolume.Capsule(a, b, values.num(v.radius))
            }
            is HitVolume.Sphere -> {
                val c = pt(v.at)
                if (c == null) {
                    report(reg.id, "sphere point ${v.at} does not resolve on '$host'")
                    return null
                }
                ResolvedVolume.Sphere(c, values.num(v.radius))
            }
            is HitVolume.Sector -> {
                val frame = if (v.frame == "self") host else v.frame
                val origin = tree.pointWorld(FrameId(frame), v.at).getOrNull()
                val fwd = axis(v.forward.name)
                val left = axis(v.left.name)
                if (origin == null || fwd == null || left == null) {
                    report(reg.id, "sector axes ${v.forward.name}/${v.left.name} do not resolve on '$host'")
                    return null
                }
                ResolvedVolume.Sector(origin, fwd, left, values.num(v.angleDeg), values.num(v.range), values.num(v.height))
            }
        }
    }

    /** World direction of an axis name ([Axes]); `caster.forward` is the full look (pitch included), not the yaw-only frame. */
    private fun axisOf(name: String): Vec3? =
        if (name == "caster.forward") lookDirection() else Axes.resolve(tree, name)

    // --------------------------------------------------------------- contacts

    private fun prepareContact(e: CastEvent) {
        val c = e.contact ?: return
        val contactId = FrameId("contact")
        if (c.victim == NIL_VICTIM) {
            placeContact(contactId, FrameId("world"), Frame(c.pointWorld, Basis.facing(c.normalWorld)))
            return
        }
        victims += c.victim
        lastVictim = c.victim
        refreshEnemy(c.victim)
        placeContact(contactId, FrameId("enemy@${c.victim}"), Frame(c.pointLocal, Basis.facing(c.normalLocal)))
    }

    /** Move the shared `contact` frame; whatever is already stuck to the previous contact keeps its own copy. */
    private fun placeContact(contact: FrameId, parent: FrameId, local: Frame) {
        val existing = tree.node(contact)
        if (existing == null) {
            addNode(FrameNode(contact, FrameKind.CONTACT, parent, local))?.let { report(null, "contact frame: ${it.reasonText()}") }
            // `at: contact.point` in presentation ops (impact sound / particles) names this point; without it the ref never resolved.
            tree.definePoint(FramePoint(contact, "point", Vec3.ZERO))
            return
        }
        val kids = tree.children(contact)
        if (kids.isNotEmpty()) {
            val old = FrameId("contact#${++contactSeq}")
            addNode(FrameNode(old, FrameKind.CONTACT, existing.parent, existing.local))
            for (k in kids) {
                val kl = tree.node(k)?.local ?: continue
                tree.reparent(k, old, keepWorld = false, snap = kl)
            }
        }
        tree.reparent(contact, parent, keepWorld = false, snap = local)
    }

    private fun CastError.reasonText(): String = when (this) {
        is CastError.FrameSetup -> reason
        is CastError.ExprFailed -> reason
        else -> toString()
    }

    private fun refreshEnemy(victim: UUID) {
        val root = ports.entities.rootOf(victim) ?: return
        val pose = Frame.fromYawPitch(root.feet, root.yawDeg, 0.0)
        val perVictim = FrameId("enemy@$victim")
        if (tree.node(perVictim) == null) addNode(FrameNode(perVictim, FrameKind.ENEMY, FrameId("world"), pose)) else tree.setLocal(perVictim, pose)
        if (victim == lastVictim) tree.setLocal(FrameId("enemy"), pose)
    }

    private fun refreshRoots() {
        ports.entities.rootOf(request.caster)?.let { r ->
            casterYaw = r.yawDeg
            casterPitch = r.pitchDeg
            casterEye = r.eyeHeight
            tree.setLocal(FrameId("caster"), Frame.fromYawPitch(r.feet, r.yawDeg, 0.0))
            tree.definePoint(FramePoint(FrameId("caster"), "eye", Vec3(0.0, r.eyeHeight, 0.0)))
            tree.setLocal(FrameId("aim"), Frame.fromYawPitch(Vec3(r.feet.x, r.feet.y + r.eyeHeight, r.feet.z), r.yawDeg, r.pitchDeg))
        }
        for (v in victims) refreshEnemy(v)
    }

    // ------------------------------------------------------------ expressions

    override fun lookDirection(): Vec3 = Frame.fromYawPitch(Vec3.ZERO, casterYaw, casterPitch).forward()

    fun eye(): Vec3 = tree.pointWorld(FrameId("caster"), "eye").getOrNull() ?: (request.casterFeet + Vec3(0.0, casterEye, 0.0))

    /** The expression environment of this instant (shape declared by [com.astarworks.astera.core.application.skillengine.load.SkillExprSpec]). */
    fun exprEnv(): ExprEnv {
        val vars = HashMap<String, ExprValue>(params.size + exposed.size + 12)
        for ((k, v) in params) {
            vars[k] = when (v) {
                is ParamValue.Num -> ExprValue.Num(v.value)
                is ParamValue.Text -> ExprValue.Str(v.value)
                is ParamValue.Flag -> ExprValue.Bool(v.value)
            }
        }
        for ((k, v) in exposed) vars[k] = ExprValue.Num(v)
        vars["tick"] = ExprValue.Num(ticksSinceStart.toDouble())
        vars["event.name"] = ExprValue.Str(currentEvent?.name ?: "")
        vars["event.source"] = ExprValue.Str(currentEvent?.source ?: "")
        vars["world.up"] = ExprValue.Vec3(UP)
        vars["i"] = ExprValue.Num(copyIndex.toDouble())
        vars["n"] = ExprValue.Num(copyCount.toDouble())
        vars["incoming.amount"] = ExprValue.Num(incomingAmount)
        activeContact()?.let { c ->
            vars["contact.point"] = ExprValue.Vec3(c.pointWorld)
            vars["contact.normal"] = ExprValue.Vec3(c.normalWorld)
            vars["contact.rel_velocity"] = ExprValue.Vec3(c.relVelocity)
            vars["contact.block"] = ExprValue.Str(c.part ?: "")
        }
        return ExprEnv(vars, frameLookup, request.seed xor (tick.toLong() shl 24), exprHost, request.seed, copySample)
    }

    private val frameLookup = object : FrameLookup {
        override fun worldPose(frame: String, point: String?): Frame? {
            if (frame == "victim") return if (point == "feet") victimFeet()?.let { Frame(it) } else victimCentre()?.let { Frame(it) }
            val id = FrameId(frame)
            val world = tree.worldOf(id).getOrNull() ?: return null
            if (point == null) return world
            val p = tree.pointWorld(id, point).getOrNull() ?: return null
            return Frame(p, world.rotation)
        }

        override fun velocity(frame: String, point: String?): Vec3 = lastVel[frame] ?: Vec3.ZERO
    }

    private val exprHost = object : ExprHost {
        override fun aimPoint(caster: ExprValue.Ref, range: Double): Vec3 = aimAt(range)

        override fun lookDirection(caster: ExprValue.Ref): Vec3 = lookDirection()

        override fun counter(key: String): Double = counters[key] ?: 0.0

        private fun enemies(from: Vec3, radius: Double): List<HitCandidate> =
            ports.entities.nearby(from, radius, request.caster).filter { !isAlly(it.entityId) }

        override fun nearestEnemyDist(from: Vec3): Double =
            enemies(from, NEAREST_SCAN).minOfOrNull { it.feet.distance(from) } ?: Double.MAX_VALUE

        override fun enemyInCone(from: Vec3, dir: Vec3, range: Double, halfAngleDeg: Double): Boolean {
            val flat = Vec3(dir.x, 0.0, dir.z)
            if (flat.length() < 1e-9) return false
            val facing = flat.normalize()
            val cosMin = kotlin.math.cos(Math.toRadians(halfAngleDeg))
            return enemies(from, range + 2.0).any { c ->
                if (c.feet.distance(from) > range) return@any false
                val to = Vec3(c.box.center.x - from.x, 0.0, c.box.center.z - from.z)
                val len = to.length()
                len < 1e-6 || facing.dot(to * (1.0 / len)) >= cosMin
            }
        }

        override fun hpRatio(who: ExprValue.Ref): Double {
            val entity = when (who.name) {
                "caster" -> request.caster
                "victim" -> currentVictim ?: throw ValueResolveException("hp_ratio(victim): no victim in scope")
                "enemy" -> lastVictim ?: throw ValueResolveException("hp_ratio(enemy): no enemy has been struck yet")
                else -> who.name.removePrefix("enemy@").takeIf { it != who.name }
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: throw ValueResolveException("hp_ratio(${who.name}): not an entity (caster, victim, enemy or enemy@<uuid>)")
            }
            return ports.entities.healthRatio(entity).coerceIn(0.0, 1.0)
        }

        override fun bladeLen(frame: ExprValue.Ref): Double {
            val id = FrameId(frame.name)
            fun off(p: String): Vec3? = tree.point(id, p)?.offset
            val tip = off("tip") ?: return 0.0
            val from = off("grip") ?: off("root") ?: return 0.0
            return from.distance(tip)
        }
    }

    private fun victimFeet(): Vec3? = currentVictim?.let { ports.entities.rootOf(it)?.feet }

    private fun victimCentre(): Vec3? {
        val v = currentVictim ?: return null
        val root = ports.entities.rootOf(v) ?: return null
        return Vec3(root.feet.x, root.feet.y + root.height * 0.5, root.feet.z)
    }

    /** Point on the caster's look ray at [range], pulled back to the first block or body the ray meets. */
    private fun aimAt(range: Double): Vec3 {
        val from = eye()
        val dir = lookDirection()
        val to = from + dir * range
        var best = range
        ports.entities.raycastBlock(from, to)?.let { best = minOf(best, from.distance(it.point)) }
        for (c in ports.entities.nearby(from + dir * (range * 0.5), range * 0.5 + 2.0, request.caster)) {
            rayBox(from, dir, c.box)?.let { t -> if (t <= best) best = t }
        }
        return from + dir * best
    }

    private fun rayBox(o: Vec3, d: Vec3, box: HitBox): Double? {
        var tMin = 0.0
        var tMax = Double.MAX_VALUE
        fun slab(oc: Double, dc: Double, lo: Double, hi: Double): Boolean {
            if (kotlin.math.abs(dc) < 1e-12) return oc in lo..hi
            val a = (lo - oc) / dc
            val b = (hi - oc) / dc
            tMin = maxOf(tMin, minOf(a, b))
            tMax = minOf(tMax, maxOf(a, b))
            return tMin <= tMax
        }
        if (!slab(o.x, d.x, box.min.x, box.max.x) || !slab(o.y, d.y, box.min.y, box.max.y) || !slab(o.z, d.z, box.min.z, box.max.z)) return null
        return tMin
    }

    private fun takeSnapshot() {
        val next = HashMap<String, Vec3>()
        val vel = HashMap<String, Vec3>()
        for (n in nodeNames) {
            val p = tree.pointWorld(FrameId(n), null).getOrNull() ?: continue
            next[n] = p
            snapshot[n]?.let { vel[n] = p - it }
        }
        snapshot = next
        lastVel = vel
    }

    /** Candidates helper kept for observers: hitbox candidates near [point]. */
    fun nearbyCandidates(point: Vec3, radius: Double): List<HitCandidate> = ports.entities.nearby(point, radius, request.caster)

    companion object {
        private const val MAX_EVENTS_PER_DRAIN = 256
        private const val MAX_CHAIN = 4

        /**
         * Statuses that end a running cast: `silence` (v1 refused casts under it) and `shock` (the electric stun, kind
         * paralysis: "locks the target out of actions"). The same ids reject a cast at start (see [DefaultCastRunner]).
         */
        val INTERRUPTING_STATUSES: List<String> = listOf("silence", "shock")
        private const val NEAREST_SCAN = 64.0

        /** A swept hit volume that moved further than this in one tick was teleported, not flown: test the point only. */
        private const val MAX_SWEEP = 16.0

        /** Hand / body points of the caster frame (feet origin, yaw only; local +X is left). */
        private val CASTER_POINTS: List<Pair<String, Vec3>> = listOf(
            "right_hand" to Vec3(-0.4, 1.1, 0.35),
            "left_hand" to Vec3(0.4, 1.1, 0.35),
            "head" to Vec3(0.0, 1.62, 0.0),
            "chest" to Vec3(0.0, 1.2, 0.0),
            "feet" to Vec3(0.0, 0.0, 0.0),
        )
    }
}
