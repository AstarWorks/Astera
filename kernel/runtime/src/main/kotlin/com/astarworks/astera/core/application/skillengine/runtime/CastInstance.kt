package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
import com.astarworks.astera.core.domain.skillengine.model.FrameDecl
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.SkillEvents
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.motion.MotionContext
import com.astarworks.astera.core.domain.skillengine.motion.MotionLaw
import com.astarworks.astera.core.domain.skillengine.motion.MotionPhase
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.motion.MotionStep
import com.astarworks.astera.core.domain.skillengine.op.HitOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import java.util.UUID

private val NIL_VICTIM: UUID = UUID(0L, 0L)

/**
 * One running cast (ADR-0299): its frame tree, motion laws, hit volumes, scheduled events, timelines and
 * presentation updaters. Casts in progress keep their [CompiledSkill], so a per-skill reload only affects
 * casts started afterwards.
 *
 * Tick order (design 3.7): external + scheduled events -> timeline rows (gameplay, presentation) -> motion
 * laws -> hit volumes -> after each phase the raised events drain (gameplay handlers then presentation
 * handlers per event) -> presentation updaters -> snapshot / observer.
 */
internal class CastInstance(
    val id: CastId,
    override val request: CastRequest,
    private val deps: CastRunnerDeps,
    private val bank: ExprBank,
    private val diagnose: (CastDiagnostic) -> Unit,
) : CastOpContext {

    private class RunningLaw(val frame: String, val law: MotionLaw, val startTick: Int, val opId: String?)
    private class HitReg(val op: HitOp.Hit, val frame: String, var prevAnchor: Vec3? = null)
    private class Scheduled(val dueTick: Int, val event: String, val source: String?)

    override val skill: CompiledSkill = request.skill
    private val def = skill.def

    override val cast: CastId get() = id
    override val tree: FrameTree = deps.newTree()
    override val ports = deps.ports
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

    override val env: CastEnv = CastEnv(this)
    private val envSpec = ExprBank.envSpec(
        def,
        exposed.keys,
        def.frames.mapTo(LinkedHashSet()) { it.id } + setOf("world", "caster", "enemy", "contact"),
    )
    override val values: ValueResolver = CastValueResolver(this, bank, envSpec)

    // --- state ---
    override var tick: Int = request.startTick
        private set
    override val ticksSinceStart: Int get() = tick - request.startTick
    var currentEvent: CastEvent? = null
        private set
    override val event: CastEvent? get() = currentEvent
    override var currentOpId: String? = null
        private set
    var ended: Boolean = false
        private set

    private val nodeNames = LinkedHashSet<String>()
    private val laws = ArrayList<RunningLaw>()
    private val hitRegs = ArrayList<HitReg>()
    private val hitSeen = HashMap<String, Int>()
    private val contactedOps = HashSet<String>()
    private val blockedOps = HashSet<String>()
    private val scheduled = ArrayList<Scheduled>()
    private val updaters = LinkedHashMap<String, PresentationUpdater>()
    private val frozenUntil = HashMap<String, Int>()
    private val queue = ArrayDeque<CastEvent>()
    private val tickEvents = ArrayList<CastEvent>()
    private val victims = LinkedHashSet<UUID>()
    private var lastVictim: UUID? = null
    private var contactSeq = 0
    private var snapshot: Map<String, Vec3> = emptyMap()
    private var lastVel: Map<String, Vec3> = emptyMap()
    private var finishRequested = false
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
        addNode(FrameNode(FrameId("enemy"), FrameKind.ENEMY, FrameId("world"), Frame(Vec3.ZERO)))?.let { return it }
        for ((name, off) in CASTER_POINTS) tree.definePoint(FramePoint(FrameId("caster"), name, off))
        tree.definePoint(FramePoint(FrameId("caster"), "eye", Vec3(0.0, casterEye, 0.0)))

        val models = HashMap<String, String?>()
        var pending = def.frames.toList()
        while (pending.isNotEmpty()) {
            val next = ArrayList<FrameDecl>()
            var progressed = false
            for (d in pending) {
                if (d.parent.frame !in nodeNames) {
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
        addNode(FrameNode(FrameId(d.id), d.kind, FrameId(d.parent.frame), local))?.let { return it }
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
        refreshRoots()

        // external + scheduled events
        val due = scheduled.filter { it.dueTick <= tick }
        scheduled.removeAll(due.toSet())
        for (s in due) queue += CastEvent(s.event, tick, s.source)
        drain()

        // timeline rows (gameplay first, then presentation)
        for (row in gameplayTimeline.due(ticksSinceStart, ::eventSince, bus::hasRaised)) runRow(row.whenExpr, row.ops)
        for (row in presentationTimeline.due(ticksSinceStart, ::eventSince, bus::hasRaised)) runRow(row.whenExpr, row.ops)
        drain()

        stepLaws()
        drain()

        resolveHits()
        drain()

        runUpdaters()

        if (finishRequested || ticksSinceStart >= deps.maxCastTicks || idle()) finishNow()
        takeSnapshot()
        deps.observer?.onTick(id, tick, tree, nodeNames.toSet(), tickEvents.toList())
    }

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

    private fun idle(): Boolean =
        laws.isEmpty() && scheduled.isEmpty() && queue.isEmpty() && updaters.isEmpty() &&
            !gameplayTimeline.pending() && !presentationTimeline.pending()

    private fun finishNow() {
        if (ended) return
        queue += CastEvent(SkillEvents.END, tick, source = null)
        drain()
        for ((key, u) in updaters.toList()) {
            updaters.remove(key)
            safeDispose(key, u)
        }
        laws.clear()
        hitRegs.clear()
        ended = true
    }

    fun cancel() {
        if (ended) return
        if (tick < request.startTick) tick = request.startTick
        finishNow()
        takeSnapshot()
        deps.observer?.onTick(id, tick, tree, nodeNames.toSet(), tickEvents.toList())
    }

    fun enqueueExternal(event: CastEvent) {
        if (!ended) queue += event.copy(tick = maxOf(event.tick, lastTick))
    }

    // ---------------------------------------------------------------- events

    private fun drain() {
        var guard = 0
        while (queue.isNotEmpty()) {
            if (guard++ > MAX_EVENTS_PER_DRAIN) {
                report(null, "event storm: more than $MAX_EVENTS_PER_DRAIN events in one phase, dropping ${queue.size}")
                queue.clear()
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
            bus.raise(e)
            def.gameplay.on[e.name]?.let { run(it) }
            def.presentation.on[e.name]?.let { run(it) }
        } finally {
            currentEvent = null
        }
    }

    override fun raise(event: String) {
        queue += CastEvent(event, tick, source = currentOpId)
    }

    override fun hasRaised(name: String): Boolean = bus.hasRaised(name)

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

    // ----------------------------------------------------------- CastOpContext

    override fun frameWorld(frame: String): Frame? = tree.worldOf(FrameId(frame)).getOrNull()

    override fun startMotion(frame: String, spec: MotionSpec) {
        when (spec) {
            is MotionSpec.Stick, is MotionSpec.Reel -> laws.removeAll { it.frame == frame }
            is MotionSpec.Fly, is MotionSpec.Path, is MotionSpec.Follow ->
                laws.removeAll { it.frame == frame && it.law.phase == MotionPhase.TRANSLATE }
            is MotionSpec.Pose, is MotionSpec.Spin -> Unit
        }
        laws += RunningLaw(frame, deps.motion.create(FrameId(frame), spec, values), tick, currentOpId)
    }

    override fun stopMotion(frame: String) {
        laws.removeAll { it.frame == frame }
    }

    override fun registerHit(op: HitOp.Hit) {
        for (f in op.frames) {
            hitRegs.removeAll { it.op.id == op.id && it.frame == f }
            hitRegs += HitReg(op, f)
        }
    }

    override fun unregisterHit(opId: String) {
        hitRegs.removeAll { it.op.id == opId }
    }

    override fun schedule(afterTicks: Int, event: String) {
        scheduled += Scheduled(tick + maxOf(0, afterTicks), event, currentOpId)
    }

    override fun freeze(frames: List<String>, ticks: Int) {
        for (f in frames) frozenUntil[f] = tick + ticks
    }

    override fun registerUpdater(key: String, updater: PresentationUpdater) {
        updaters.remove(key)?.let { safeDispose(key, it) }
        updaters[key] = updater
    }

    override fun removeUpdater(key: String): Boolean {
        val u = updaters.remove(key) ?: return false
        safeDispose(key, u)
        return true
    }

    override fun finish() {
        finishRequested = true
    }

    private fun safeDispose(key: String, u: PresentationUpdater) {
        try {
            u.dispose(this)
        } catch (e: Exception) {
            report(key, "dispose failed: ${e.message}")
        }
    }

    private fun runUpdaters() {
        for ((key, u) in updaters.toList()) {
            currentOpId = key
            val keep = try {
                u.update(this)
            } catch (e: Exception) {
                report(key, "update failed: ${e.message}")
                false
            }
            if (!keep && updaters.remove(key) != null) safeDispose(key, u)
        }
        currentOpId = null
    }

    // ------------------------------------------------------------------ laws

    private fun isFrozen(frame: String): Boolean {
        if (frozenUntil.isEmpty()) return false
        var f: FrameId? = FrameId(frame)
        while (f != null) {
            if ((frozenUntil[f.value] ?: -1) >= tick) return true
            f = tree.node(f)?.parent
        }
        return false
    }

    private fun depth(frame: String): Int {
        var d = 0
        var f = tree.node(FrameId(frame))?.parent
        while (f != null) {
            d++
            f = tree.node(f)?.parent
        }
        return d
    }

    private fun stepLaws() {
        if (laws.isEmpty()) return
        val byFrame = laws.groupBy { it.frame }
        for (frame in byFrame.keys.sortedBy { depth(it) }) {
            if (isFrozen(frame)) continue
            for (running in byFrame.getValue(frame).sortedBy { it.law.phase.ordinal }) {
                if (running !in laws) continue
                val fid = FrameId(frame)
                val node = tree.node(fid)
                val world = tree.worldOf(fid).getOrNull()
                if (node == null || world == null) {
                    report(running.opId, "motion on missing frame '$frame' stopped")
                    laws.remove(running)
                    continue
                }
                val ctx = LawContext(fid, node.local, world, running.startTick)
                val step = try {
                    running.law.step(ctx)
                } catch (e: ValueResolveException) {
                    report(running.opId, "motion on '$frame': ${e.message}")
                    laws.remove(running)
                    continue
                } catch (e: Exception) {
                    report(running.opId, "motion on '$frame': ${e::class.simpleName} ${e.message}")
                    laws.remove(running)
                    continue
                }
                when (step) {
                    MotionStep.Hold -> Unit
                    is MotionStep.Move -> tree.setLocal(fid, step.local)
                    is MotionStep.Finish -> {
                        step.local?.let { tree.setLocal(fid, it) }
                        laws.remove(running)
                        step.event?.let { queue += CastEvent(it, tick, source = running.opId ?: frame) }
                    }
                }
            }
        }
    }

    private inner class LawContext(
        override val frame: FrameId,
        override val local: Frame,
        override val world: Frame,
        private val startTick: Int,
    ) : MotionContext {
        override val tick: Int get() = this@CastInstance.tick
        override val ticksSinceStart: Int get() = this@CastInstance.tick - startTick
        override val tree: FrameTree get() = this@CastInstance.tree
        override val env get() = this@CastInstance.env
    }

    // ------------------------------------------------------------------ hits

    private fun resolveHits() {
        if (hitRegs.isEmpty()) return
        for (reg in hitRegs.toList()) {
            if (isFrozen(reg.frame)) continue
            val volume = try {
                resolveVolume(reg)
            } catch (e: ValueResolveException) {
                report(reg.op.id, "hit volume: ${e.message}")
                null
            } ?: continue
            val hitterVel = hitterVelocity(reg.frame)
            val candidates = ports.entities.nearby(volume.broadCenter, volume.broadRadius, request.caster)
            val contacts = deps.contacts.narrow(reg.op.id, tick, reg.frame, volume, hitterVel, candidates)
            var contacted = false
            for (c in contacts) {
                if (!windowAccepts(reg, c.victim)) continue
                contacted = true
                contactedOps += reg.op.id
                queue += CastEvent(SkillEvents.HIT, tick, source = reg.op.id, contact = c)
            }
            val anchor = anchorOf(volume)
            val prev = reg.prevAnchor
            reg.prevAnchor = anchor
            if (!contacted && prev != null && anchor != null && prev != anchor &&
                reg.op.id !in contactedOps && reg.op.id !in blockedOps
            ) {
                ports.entities.raycastBlock(prev, anchor)?.let { hit ->
                    blockedOps += reg.op.id
                    queue += CastEvent(SkillEvents.BLOCK, tick, source = reg.op.id, contact = blockContact(reg, hit.point, hit.normal, hit.blockKey, hitterVel))
                }
            }
        }
    }

    private fun blockContact(reg: HitReg, point: Vec3, normal: Vec3, key: String, vel: Vec3) = ContactEvent(
        hitId = reg.op.id, tick = tick, victim = NIL_VICTIM, hitterFrame = reg.frame, part = key,
        pointWorld = point, normalWorld = normal, pointLocal = point, normalLocal = normal, relVelocity = vel,
    )

    private fun windowAccepts(reg: HitReg, victim: UUID): Boolean {
        val op = reg.op
        return when (val w = op.window) {
            HitWindow.Once -> hitSeen.putIfAbsent("${op.id}|$victim", tick) == null
            HitWindow.PerPart -> hitSeen.putIfAbsent("${op.id}|${reg.frame}|$victim", tick) == null
            is HitWindow.EveryTicks -> {
                val key = "${op.id}|${reg.frame}|$victim"
                val last = hitSeen[key]
                if (last == null || tick - last >= w.ticks) {
                    hitSeen[key] = tick
                    true
                } else {
                    false
                }
            }
        }
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

    private fun resolveVolume(reg: HitReg): ResolvedVolume? {
        val host = reg.frame
        fun pt(ref: PointRef): Vec3? =
            tree.pointWorld(FrameId(if (ref.frame == "self") host else ref.frame), ref.point).getOrNull()
        fun axis(name: String): Vec3? = env.axis(if (name.startsWith("self.")) host + name.removePrefix("self") else name)
        return when (val v = reg.op.volume) {
            is HitVolume.Capsule -> {
                val a = pt(v.from)
                val b = pt(v.to)
                if (a == null || b == null) {
                    report(reg.op.id, "capsule points ${v.from}/${v.to} do not resolve on '$host'")
                    return null
                }
                ResolvedVolume.Capsule(a, b, values.num(v.radius))
            }
            is HitVolume.Sphere -> {
                val c = pt(v.at)
                if (c == null) {
                    report(reg.op.id, "sphere point ${v.at} does not resolve on '$host'")
                    return null
                }
                ResolvedVolume.Sphere(c, values.num(v.radius))
            }
            is HitVolume.Sector -> {
                val frame = if (v.frame == "self") host else v.frame
                val origin = tree.pointWorld(FrameId(frame), null).getOrNull()
                val fwd = axis(v.forward.name)
                val left = axis(v.left.name)
                if (origin == null || fwd == null || left == null) {
                    report(reg.op.id, "sector axes ${v.forward.name}/${v.left.name} do not resolve on '$host'")
                    return null
                }
                ResolvedVolume.Sector(origin, fwd, left, values.num(v.angleDeg), values.num(v.range), values.num(v.height))
            }
        }
    }

    // --------------------------------------------------------------- contacts

    fun currentVictim(): UUID? {
        val v = currentEvent?.contact?.victim
        return if (v != null && v != NIL_VICTIM) v else lastVictim
    }

    private fun prepareContact(e: CastEvent) {
        val c = e.contact ?: return
        val contactId = FrameId("contact")
        if (c.victim == NIL_VICTIM) {
            placeContact(contactId, FrameId("world"), Frame(c.pointWorld, Quaternion.lookRotation(c.normalWorld)))
            return
        }
        victims += c.victim
        lastVictim = c.victim
        refreshEnemy(c.victim)
        placeContact(contactId, FrameId("enemy@${c.victim}"), Frame(c.pointLocal, Quaternion.lookRotation(c.normalLocal)))
    }

    /** Move the shared `contact` frame; whatever is already stuck to the previous contact keeps its own copy. */
    private fun placeContact(contact: FrameId, parent: FrameId, local: Frame) {
        val existing = tree.node(contact)
        if (existing == null) {
            addNode(FrameNode(contact, FrameKind.CONTACT, parent, local))?.let { report(null, "contact frame: ${it.reasonText()}") }
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
        }
        for (v in victims) refreshEnemy(v)
    }

    // ------------------------------------------------------------- env helpers

    fun hasNode(name: String): Boolean = name in nodeNames || tree.node(FrameId(name)) != null

    fun lastVelocity(frame: String): Vec3 = lastVel[frame] ?: Vec3.ZERO

    fun lookDirection(): Vec3 = Frame.fromYawPitch(Vec3.ZERO, casterYaw, casterPitch).forward()

    fun eye(): Vec3 = tree.pointWorld(FrameId("caster"), "eye").getOrNull() ?: (request.casterFeet + Vec3(0.0, casterEye, 0.0))

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
