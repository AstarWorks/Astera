package com.astarworks.astera.combat.application.service.vfx


import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IVfxRegistry
import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.effect.vfx.VfxBodyContext
import com.astarworks.astera.core.domain.presentation.PresentationAudience
import com.astarworks.astera.core.domain.presentation.PresentationDisplay
import com.astarworks.astera.core.domain.presentation.PresentationKind
import com.astarworks.astera.core.domain.presentation.PresentationLease
import com.astarworks.astera.core.domain.presentation.PresentationMask
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.vfx.SpriteFrames
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxElementPlan
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxParticleEmission
import com.astarworks.astera.core.domain.effect.vfx.VfxPlan
import com.astarworks.astera.core.domain.effect.vfx.VfxQuad
import com.astarworks.astera.core.domain.effect.vfx.VfxTargetKey
import com.astarworks.astera.core.domain.effect.vfx.targetWeightAt
import com.astarworks.astera.core.domain.effect.vfx.VfxTimeline
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger

/** Why [VfxPlayer.play] did not start an effect. */
public sealed class VfxPlayError {
    public data class UnknownVfx(val id: VfxId) : VfxPlayError()
    public data class InvalidTargetTracking(val id: VfxId) : VfxPlayError()

    /** The effect names a `body:` animation that is not loaded. */
    public data class UnknownBody(val id: VfxId, val animation: CustomAnimationId) : VfxPlayError()

    /** A performance could not take the caster's hand / ride the caster (offline, attachment refused). */
    public data class PerformanceUnavailable(val id: VfxId) : VfxPlayError()

    /** Starting would exceed the server-wide live display cap. */
    public data class OverCapacity(val reserved: Int, val requested: Int, val cap: Int) : VfxPlayError()
}

/** One effect instance to start. */
public class VfxRequest(
    public val vfxId: VfxId,
    public val world: WorldId,
    /** Anchor position at start. */
    public val origin: Vec3,
    /** Caster look direction (for `orient: look / yaw`). */
    public val look: Vec3?,
    /** Caster -> anchor direction (for `orient: to_target`). */
    public val toTarget: Vec3?,
    public val scale: Double = 1.0,
    /** Overrides the definition's `follow`; `null` keeps it. */
    public val follow: Boolean? = null,
    /** Live anchor position for `follow`; `null` result = keep the last known one. */
    public val anchor: (() -> Vec3?)? = null,
    public val seed: Long = 0L,
    /** The caster, for particle `viewers: caster|others`. */
    public val caster: PlayerId? = null,
    /** Hit-stop windows (effect ticks) that freeze rigs with `respect_hit_stop`. */
    public val freeze: List<VfxFreeze> = emptyList(),
    /** Entity identity for optional native attachment; location anchors leave this null. */
    public val anchorEntityId: PlayerId? = null,
    /** Bound live target feet; null result permanently stops this effect. */
    public val target: (() -> Vec3?)? = null,
)

/** One particle element emission handed to the runtime (which plans it through `ParticleEmitter`). */
public class VfxParticleCall(
    public val world: WorldId,
    public val at: Vec3,
    public val particleId: ParticleId,
    public val look: Vec3?,
    public val tangent: Vec3?,
    public val center: Vec3?,
    public val toward: Vec3?,
    public val source: PlayerId?,
)

/**
 * Runs `content/vfx` choreographies (ADR-0220): plans a definition with [VfxTimeline], then
 * replays the plan tick by tick through [IDisplayEntityGateway] -- spawn at the anchor, send
 * the planned (client-interpolated) transform updates, emit particles, teleport for `follow`,
 * remove at the end. One shared repeating task drives every live instance.
 *
 * At most [maxLiveDisplays] display entities are reserved at once; further instances are
 * dropped (`OverCapacity`). [shutdown] removes everything.
 */
public class VfxPlayer(
    private val scheduler: IScheduler,
    private val displays: IDisplayEntityGateway,
    private val registry: IVfxRegistry,
    private val emitParticle: (VfxParticleCall) -> Unit,
    private val log: Logger,
    private val maxLiveDisplays: Int = MAX_LIVE_DISPLAYS,
    /** Flipbook frame counts for `sprite` elements (ADR-0239); unknown sprites play as one frame. */
    private val sprites: SpriteFrames = SpriteFrames.NONE,
    /** ADR-0276: the one owner of held-item / armor / body hiding; a performance claims a lease from it. */
    private val presentation: (() -> IPlayerPresentation?)? = null,
    /** Resolves a `body:` animation id to the skin-rig animation it names. */
    private val bodies: ((CustomAnimationId) -> SkinRigAnimation?)? = null,
) {

    private class ElementState(val plan: VfxElementPlan) {
        var handle: DisplayHandle? = null
        var nextUpdate: Int = 0
        var nextParticle: Int = 0
        var attached: Boolean = false

        /** Spawned with view range 0 in this instance: it is revealed by the lease bundle (reused passengers already are). */
        var staged: Boolean = false

        /** Bone / weapon display of a performance. */
        val performer: Boolean = plan.kind is VfxElementKind.SkinBone || plan.kind is VfxElementKind.MainHandWeapon

        /** Entity position offset from the anchor (quads sit at their own point; `0` for ordinary displays). */
        var offset: Vec3 = plan.spawnOffset
        var targetOffset: Vec3 = Vec3.ZERO
        var authoredTransform: DisplayTransform = plan.initial
        var lineCorrection: Vec3? = null

        /** Quad updates move the entity themselves, so the per-tick follow teleport would fight them. */
        val selfMoving: Boolean = plan.updates.any { it.position != null }
    }

    private class Instance(
        val request: VfxRequest, val plan: VfxPlan, val startedAt: Long,
        val targetReference: Vec3?, val targetTracks: Map<String, List<VfxTargetKey>>,
        val targetLines: Set<String>,
    ) {
        val elements: List<ElementState> = plan.elements.map(::ElementState)
        var position: Vec3 = request.origin
        var age: Int = 0

        /** The performance's presentation lease (hand / armor / body); `null` for ordinary effects. */
        var lease: PresentationLease? = null

        /** Elements were spawned hidden and wait for the reveal bundle. */
        var awaitingReveal: Boolean = false

        /** The owner leg of the release (hand back, displays scaled to zero) has not happened yet. */
        var ownerLegOpen: Boolean = false

        /**
         * Transform updates that must reach clients inside a presentation bundle (the reveal tick, and every cut tick),
         * not through the entity tracker, whose per-entity sends can straddle a client frame. The gateway applies them
         * at server tick end, just before it takes the entity data.
         */
        val bundled: MutableMap<DisplayHandle, () -> Unit> = HashMap()

        /** Handles whose update of this tick goes into the next bundle. */
        var deferUpdates: Set<DisplayHandle> = emptySet()

        /** Age at which the reveal bundle was queued. */
        var revealedAge: Int = -1
    }

    private val instances: MutableList<Instance> = ArrayList()
    private var reserved: Int = 0
    private var ticker: IScheduler.TaskHandle? = null

    /** Number of display entities currently reserved by live effects. */
    public val liveDisplays: Int get() = reserved

    public fun play(request: VfxRequest): Result<Unit, VfxPlayError> =
        ExecutionCosts.measure("vfx.play", request.vfxId.value) { playEffect(request) }

    private fun playEffect(request: VfxRequest): Result<Unit, VfxPlayError> {
        val def = registry.find(request.vfxId) ?: return Result.failure(VfxPlayError.UnknownVfx(request.vfxId))
        // Capture-time switch (vfx-shot SHOT_NO_FX=1): motion-only footage plays the performance (body + weapon) and nothing else.
        if (PERFORMANCE_ONLY && def.body == null && def.rigs.none { it.weapon != null }) return Result.success(Unit)
        if ((request.target != null) != (def.targetReference != null) ||
            (request.target != null && ((request.follow ?: def.follow) || request.target.invoke() == null))) {
            return Result.failure(VfxPlayError.InvalidTargetTracking(request.vfxId))
        }
        val frame = def.orient.frame(request.origin, request.look, request.toTarget)
        val body = def.body?.let { id ->
            val animation = bodies?.invoke(id) ?: return Result.failure(VfxPlayError.UnknownBody(request.vfxId, id))
            VfxBodyContext(animation, request.anchorEntityId?.let { displays.isSlimSkin(it) } ?: false)
        }
        val planned = VfxTimeline.plan(def, frame, request.seed, request.scale, sprites, request.freeze, body)
        val plan = request.follow?.let { planned.copy(follow = it) } ?: planned
        if (plan.performance && (request.anchorEntityId == null || !plan.attachToAnchor)) {
            return Result.failure(VfxPlayError.PerformanceUnavailable(request.vfxId))
        }
        val replaced = if (plan.follow && plan.replaceGroup != null && request.anchorEntityId != null) {
            instances.filter { it.plan.follow && it.plan.replaceGroup == plan.replaceGroup &&
                it.request.anchorEntityId == request.anchorEntityId && it.request.world == request.world }
        } else emptyList()
        val remaining = reserved - replaced.sumOf { it.plan.displayCount }
        if (remaining + plan.displayCount > maxLiveDisplays) {
            log.debug("VFX {} dropped: {} after replacement + {} displays exceed cap {}", request.vfxId, remaining, plan.displayCount, maxLiveDisplays)
            return Result.failure(VfxPlayError.OverCapacity(remaining, plan.displayCount, maxLiveDisplays))
        }
        // Keep the current effect if the new plan cannot fit; only replace after admission.
        val instance = Instance(request, plan, scheduler.currentTick(),
            def.targetReference?.let { frame.rotation.rotate(it * request.scale) },
            def.elements.filter { it.targetTrack.isNotEmpty() }.associate { it.id to it.targetTrack },
            def.elements.filter { it.targetLine }.map { it.id }.toSet())
        if (plan.attachToAnchor && request.anchorEntityId != null) {
            val reusable = replaced.flatMap { it.elements }.filter { state ->
                state.attached && state.handle?.let {
                    displays.isAttached(it, request.anchorEntityId, request.world)
                } == true
            }.toMutableList()
            for (state in instance.elements) {
                val next = state.plan
                if (next.spawnTick != 0 || next.billboard != DisplayBillboard.FIXED) continue
                val previous = reusable.firstOrNull { old ->
                    old.plan.kind == next.kind && old.plan.billboard == next.billboard &&
                        old.plan.brightness == next.brightness && old.plan.glowArgb == next.glowArgb &&
                        old.plan.viewRange == next.viewRange
                } ?: continue
                state.handle = previous.handle
                state.attached = true
                previous.handle = null
                reusable.remove(previous)
            }
        }
        // Claim before the old performance is released: the union of both leases covers every slot, so a chained skill
        // hands the hand over with no restore (and no frame with the real weapon) in between.
        if (plan.performance) {
            val port = presentation?.invoke()
            if (port != null) {
                val bodyDrawn = plan.elements.any { it.kind is VfxElementKind.SkinBone }
                val mask = PresentationMask(hand = true, offhand = bodyDrawn, armor = bodyDrawn, body = bodyDrawn, pinPose = true)
                val owner = request.anchorEntityId!!
                val claimed = port.claim(owner, mask, PresentationKind.PERFORMANCE, onRevoked = { revoked(owner) })
                if (claimed is Result.Failure) return Result.failure(VfxPlayError.PerformanceUnavailable(request.vfxId))
                instance.lease = (claimed as Result.Success).value
                instance.awaitingReveal = true
                instance.ownerLegOpen = true
            }
        }
        replaced.forEach(::finish)
        reserved += plan.displayCount
        // The client needs one tick to render a new display, the hide needs none: spawn the displays invisible now and reveal
        // them (together with the lease's hide) at age REVEAL_AGE. A client ticks a new display only on its own 20 Hz clock, so a
        // reveal one server tick after the spawn can beat that tick (rig drawn a frame late = a frame with no weapon).
        val done = try {
            advance(instance)
        } catch (ex: IllegalStateException) {
            log.warn("VFX {} cannot start: {}", request.vfxId, ex.message)
            finish(instance, immediate = true)
            return Result.failure(VfxPlayError.PerformanceUnavailable(request.vfxId))
        }
        if (done) {
            finish(instance)
        } else {
            instances += instance
            if (ticker == null) ticker = scheduler.repeat(ExecutionScope.Global, ticks(1), ticks(1)) {
                // Equal-tick FIFO dispatch puts this after already queued dash steps.
                // Sample the anchor after movement, not halfway through the mechanic queue.
                scheduler.run(ExecutionScope.Global, ticks(0)) { tickAll() }
            }
        }
        return Result.success(Unit)
    }

    /** Ends every performance of [caster] at once (death, quit, world change): displays go in this tick, the lease is given back. */
    public fun endFor(caster: PlayerId) {
        instances.filter { it.request.anchorEntityId == caster }.forEach { finish(it, immediate = true) }
    }

    /** The presentation took [caster]'s lease away (quit, world change, preempted): drop the displays, nothing to give back. */
    private fun revoked(caster: PlayerId) {
        instances.filter { it.request.anchorEntityId == caster && it.lease != null }.forEach {
            it.lease = null
            finish(it, immediate = true)
        }
    }

    /** Removes every live display and stops ticking. */
    public fun shutdown() {
        instances.toList().forEach { finish(it, immediate = true) }
        instances.clear()
        ticker?.cancel()
        ticker = null
    }

    private fun tickAll() {
        for (instance in instances.toList()) {
            scheduler.run(instance.request.anchorEntityId?.let { ExecutionScope.Entity(it.uuid) } ?: regionScope(instance.request.world, instance.position), kotlin.time.Duration.ZERO) {

                // play() already processed age 0. A delayed cast can precede our shared ticker
                // in this same server tick; it must not immediately consume age 1 as well.
                if (instance.startedAt == scheduler.currentTick()) return@run
                instance.age++
                try {
                    if (advance(instance)) finish(instance)
                } catch (ex: Exception) {
                    log.warn("VFX {} failed at age {}; stopping it", instance.request.vfxId, instance.age, ex)
                    finish(instance)
                }
            }
        }
        if (instances.isEmpty()) {
            ticker?.cancel()
            ticker = null
        }
    }

    /** Processes tick [Instance.age]; returns `true` once the effect is over. */
    private fun advance(instance: Instance): Boolean =
        ExecutionCosts.measure("vfx.tick", instance.request.vfxId.value) { advanceEffect(instance) }

    private fun advanceEffect(instance: Instance): Boolean {
        val age = instance.age
        val plan = instance.plan
        // Safety net: a bundle that never ran (host without packets, rig ended first) must not drop the update.
        if (instance.bundled.isNotEmpty()) instance.bundled.values.toList().forEach { it() }.also { instance.bundled.clear() }
        if (instance.awaitingReveal && age == minOf(REVEAL_AGE, plan.durationTicks - 1).coerceAtLeast(1)) reveal(instance)
        // The owner gets the hand back a tick before the displays go (they are scaled to zero for the owner in the same client
        // step); other viewers get it in the bundle that removes the displays (releaseOthersLeg).
        if (instance.ownerLegOpen && age >= plan.durationTicks - 1) releaseOwnerLeg(instance)
        if (instance.lease != null && age >= (instance.elements.filter { it.handle != null }.minOfOrNull { it.plan.despawnTick } ?: Int.MAX_VALUE)) {
            releaseOthersLeg(instance)
        }
        val targetCorrection = instance.request.target?.let { target ->
            val live = target() ?: return true
            live - (instance.position + (instance.targetReference ?: return true))
        }
        val anchor = instance.request.anchor
        if (plan.follow && anchor != null) {
            anchor()?.let { instance.position = it }
            for (state in instance.elements) {
                // A native passenger already rides the anchor in every client (the caster's own included); a server
                // teleport here would only re-send a pose-cache-noise offset and make it lag the camera.
                if (!state.selfMoving && !state.attached) state.handle?.let { displays.teleport(it, instance.position + state.offset, 0f, 0f, plan.followTeleportTicks ?: FOLLOW_TELEPORT_TICKS) }
            }
        }
        for (state in instance.elements) {
            val e = state.plan
            val track = instance.targetTracks[e.elementId]
            if (targetCorrection != null && track != null) {
                state.targetOffset = targetCorrection * track.targetWeightAt(age - e.spawnTick - VfxTimeline.ANIMATION_DELAY_TICKS)
            }
            if (e.elementId in instance.targetLines) state.lineCorrection = targetCorrection
            if (e.kind !is VfxElementKind.Particle && age == e.spawnTick) spawn(instance, state)
            val handle = state.handle
            if (handle != null) {
                while (state.nextUpdate < e.updates.size && e.updates[state.nextUpdate].tick <= age) {
                    val u = e.updates[state.nextUpdate++]
                    if (u.tick < age) continue
                    u.block?.let { displays.setBlock(handle, it) }
                    u.text?.let { displays.setText(handle, listOf(it)) }
                    u.style?.let { displays.setTextStyle(handle, it.backgroundArgb, it.textOpacity, it.textRgb) }
                    u.position?.let {
                        state.offset = it
                        displays.teleport(handle, instance.position + it + state.targetOffset, 0f, 0f, (if (plan.follow && !state.attached) plan.followTeleportTicks else null) ?: (u.positionTicks ?: u.interpolationTicks).coerceIn(0, MAX_TELEPORT_TICKS))
                    }
                    if (u.keepTransform) continue
                    state.authoredTransform = u.transform
                    if (state.lineCorrection == null) {
                        // The reveal tick's and every cut tick's update must not reach clients through the entity tracker: its
                        // per-entity sends are separate, a client tick can fall between them (hand one step ahead of the hilt).
                        // They join one presentation bundle instead (PresentationDisplay.beforeSend, applied at tick end).
                        val bundle = e.kind.let { it is VfxElementKind.SkinBone || it is VfxElementKind.MainHandWeapon } &&
                            instance.lease != null && (age in plan.cutTicks || handle in instance.deferUpdates)
                        if (bundle) instance.bundled[handle] = { displays.update(handle, u.transform, u.interpolationTicks) }
                        else displays.update(handle, u.transform, u.interpolationTicks)
                    }
                }
            }
            if (handle != null && state.lineCorrection != null) {
                displays.update(handle, lineTransform(state.authoredTransform, state.lineCorrection!!), 0)
            }
            if (handle != null && track != null && targetCorrection != null) {
                displays.teleport(handle, instance.position + state.offset + state.targetOffset, 0f, 0f, 0)
            }
            val kind = e.kind
            if (kind is VfxElementKind.Particle) {
                while (state.nextParticle < e.particles.size && e.particles[state.nextParticle].tick <= age) {
                    val p = e.particles[state.nextParticle++]
                    if (p.tick == age) emitParticleCalls(instance, kind, p)
                }
            }
            if (handle != null && age >= e.despawnTick) {
                displays.remove(handle)
                state.handle = null
            }
        }
        instance.deferUpdates = emptySet()
        val owner = instance.request.anchorEntityId
        val port = presentation?.invoke()
        if (instance.lease != null && owner != null && port != null && instance.bundled.isNotEmpty() && age in plan.cutTicks && instance.revealedAge != age) {
            // A cut: every performer jumps in the same client step. The updates apply at tick end, inside one bundle per viewer.
            val due = instance.bundled.toMap()
            instance.bundled.clear()
            port.flushBundled(owner, due.map { (handle, apply) -> PresentationDisplay(handle.id, null, beforeSend = apply) })
        }
        return age >= plan.durationTicks
    }

    /** Makes the staged displays visible; the lease's hide and the reveal reach every client in one packet bundle. */
    private fun reveal(instance: Instance) {
        instance.awaitingReveal = false
        instance.revealedAge = instance.age
        val lease = instance.lease ?: return
        val port = presentation?.invoke()
        val staged = instance.elements.filter { it.staged }.mapNotNull { state ->
            state.handle?.let { handle ->
                // The head bone encloses the owner's first-person camera: the owner never gets it (third person: headless rig).
                val ownHead = (state.plan.kind as? VfxElementKind.SkinBone)?.bone == SkinRigBone.HEAD
                PresentationDisplay(handle.id, state.plan.viewRange ?: DEFAULT_VIEW_RANGE, ownHead) { instance.bundled.remove(handle)?.invoke() }
            }
        }
        if (port == null) {
            staged.forEach { displays.setViewRange(DisplayHandle(it.id), it.viewRange!!) }
            return
        }
        instance.deferUpdates = staged.mapTo(HashSet()) { DisplayHandle(it.id) }
        port.reveal(lease, staged)
    }

    private fun emitParticleCalls(instance: Instance, kind: VfxElementKind.Particle, p: VfxParticleEmission) {
        val origin = instance.position
        val points = p.pathOffsets.ifEmpty { listOf(p.offset) }
        for (offset in points) {
            emitParticle(
                VfxParticleCall(
                    world = instance.request.world,
                    at = origin + offset,
                    particleId = kind.particleId,
                    look = instance.request.look,
                    tangent = p.tangent,
                    center = p.center?.let { origin + it },
                    toward = p.trailTarget?.let { origin + it },
                    source = instance.request.caster,
                ),
            )
        }
    }

    private fun spawn(instance: Instance, state: ElementState) {
        val e = state.plan
        val spec = DisplaySpec(
            kind = displayKind(e.kind) ?: return,
            transform = state.lineCorrection?.let { lineTransform(e.initial, it) } ?: e.initial,
            lifetimeTicks = (e.despawnTick - e.spawnTick).toLong().coerceAtLeast(1L),
            billboard = e.billboard,
            brightness = e.brightness,
            glowArgb = e.glowArgb,
            viewRange = if (instance.awaitingReveal && state.performer) 0f else e.viewRange,
            weaponRigShading = e.weaponRigShading,
        )
        val lines = when (val k = e.kind) {
            is VfxElementKind.Text -> listOf(k.text)
            is VfxElementKind.Quad -> listOf(VfxQuad.text(k))
            else -> emptyList()
        }
        // A replacement retains compatible passengers so the client never waits for a
        // new entity's first render. Reset animated content as well as its transform.
        state.handle?.let { handle ->
            when (val kind = spec.kind) {
                is DisplayKind.Block -> displays.setBlock(handle, kind.blockKey)
                is DisplayKind.Text -> {
                    displays.setText(handle, lines)
                    displays.setTextStyle(handle, kind.backgroundArgb, kind.textOpacity, kind.textRgb)
                }
                is DisplayKind.Item, is DisplayKind.SkinBone, DisplayKind.AnchorMainHand -> Unit // Identical kind includes item and display mode.
            }
            displays.teleport(handle, instance.position + e.spawnOffset + state.targetOffset, 0f, 0f, 0)
            displays.update(handle, e.initial, 0)
            return
        }
        val anchorId = instance.request.anchorEntityId
        if (instance.plan.follow && instance.plan.attachToAnchor && anchorId != null && e.billboard == DisplayBillboard.FIXED) {
            state.handle = displays.spawnAttached(instance.request.world, spec, lines, anchorId, e.spawnOffset)
            state.attached = state.handle != null
            if (!state.attached) log.debug("VFX {}: native attachment unavailable for '{}'", instance.request.vfxId, e.elementId)
        }
        if (state.performer && !state.attached) {
            // One transport only (ADR-0276): a performer that cannot ride the caster would drift from the hand in a dash.
            throw IllegalStateException("native attachment unavailable for performer '${e.elementId}'")
        }
        if (state.handle == null) state.handle = displays.spawn(instance.request.world, instance.position + e.spawnOffset + state.targetOffset, spec, lines)
        if (state.handle == null) log.warn("[cue-dropped] vfx: {} display for element '{}' was not spawned", instance.request.vfxId, e.elementId)
        state.staged = state.performer && spec.viewRange == 0f
    }

    /** Block transforms use corner origins. Reconstruct both centreline endpoints before stretching. */
    private fun lineTransform(t: DisplayTransform, correction: Vec3): DisplayTransform {
        val center = t.translation + t.rotation.rotate(t.scale * 0.5)
        val half = t.rotation.rotate(Vec3(0.0, 0.0, t.scale.z * 0.5))
        val start = center - half
        val end = center + half + correction
        val delta = end - start
        val length = delta.length()
        val rotation = if (length < 1e-8) t.rotation else Quaternion.lookRotation(delta, t.rotation.rotate(Vec3(0.0, 1.0, 0.0)))
        val scale = Vec3(t.scale.x, t.scale.y, length)
        return DisplayTransform((start + end) * 0.5 - rotation.rotate(scale * 0.5), rotation, scale)
    }

    private fun displayKind(kind: VfxElementKind): DisplayKind? = when (kind) {
        is VfxElementKind.Block -> DisplayKind.Block(kind.blockKey)
        is VfxElementKind.Item -> DisplayKind.Item(kind.itemId, kind.displayMode, kind.itemModel)
        is VfxElementKind.Text -> DisplayKind.Text(
            lines = listOf(LocalizedText(mapOf(LocaleCode.EN to kind.text, LocaleCode.JA to kind.text))),
            backgroundArgb = kind.backgroundArgb,
            shadow = kind.shadow,
            seeThrough = kind.seeThrough,
            lineWidth = kind.lineWidth,
            textOpacity = kind.textOpacity,
        )
        is VfxElementKind.Quad -> {
            val style = VfxQuad.style(kind, kind.colorRgb, kind.alpha)
            DisplayKind.Text(
                lines = listOf(VfxQuad.text(kind).let { q -> LocalizedText(mapOf(LocaleCode.EN to q, LocaleCode.JA to q)) }),
                backgroundArgb = style.backgroundArgb,
                shadow = false,
                seeThrough = kind.seeThrough,
                textOpacity = style.textOpacity,
                textRgb = style.textRgb,
            )
        }
        is VfxElementKind.SkinBone -> DisplayKind.SkinBone(kind.bone, kind.slim)
        is VfxElementKind.MainHandWeapon -> DisplayKind.AnchorMainHand
        is VfxElementKind.Particle -> null
        is VfxElementKind.Sprite -> error("sprite elements are resolved to text by VfxTimeline.plan")
    }

    private fun performerIds(instance: Instance): List<java.util.UUID> =
        instance.elements.filter { it.performer }.mapNotNull { it.handle?.id }

    /** Owner leg: the hand comes back and the performers are scaled to zero in the same client step, a tick before removal. */
    private fun releaseOwnerLeg(instance: Instance) {
        if (!instance.ownerLegOpen) return
        instance.ownerLegOpen = false
        val lease = instance.lease ?: return
        presentation?.invoke()?.release(lease, performerIds(instance), PresentationAudience.OWNER)
    }

    /**
     * Others leg (called BEFORE the displays are removed): hand, armor and body come back for every other viewer in the
     * bundle that removes the performers, so no frame shows the real weapon next to a performer, or neither.
     */
    private fun releaseOthersLeg(instance: Instance) {
        releaseOwnerLeg(instance)
        val lease = instance.lease ?: return
        instance.lease = null
        presentation?.invoke()?.release(lease, performerIds(instance), PresentationAudience.OTHERS)
    }

    /**
     * The single exit of every instance: normal end is already past the owner leg; a cancel (replace, anchor gone, death) gives
     * both legs back here. Unless [immediate], an abrupt end keeps the displays one more tick so the owner's hand, which only
     * updates on the client's tick, never meets a performer that is already gone.
     */
    private fun finish(instance: Instance, immediate: Boolean = false) {
        val abrupt = instance.ownerLegOpen
        val handles = instance.elements.mapNotNull { it.handle }
        releaseOthersLeg(instance)
        instance.awaitingReveal = false
        instance.bundled.clear()
        for (state in instance.elements) state.handle = null
        if (abrupt && !immediate && handles.isNotEmpty()) {
            scheduler.run(instance.request.anchorEntityId?.let { ExecutionScope.Entity(it.uuid) } ?: regionScope(instance.request.world, instance.position), ticks(1)) {
                handles.forEach(displays::remove)
            }
        } else {
            handles.forEach(displays::remove)
        }
        instances.remove(instance)
        reserved = (reserved - instance.plan.displayCount).coerceAtLeast(0)
    }

    public companion object {
        /** `ASTERA_VFX_PERFORMANCE_ONLY=1`: skip every effect that is not a performance (set by `SHOT_NO_FX=1`; never in production). */
        private val PERFORMANCE_ONLY: Boolean = System.getenv("ASTERA_VFX_PERFORMANCE_ONLY") == "1"

        /** Server-wide cap on display entities reserved by running effects. */
        public const val MAX_LIVE_DISPLAYS: Int = 600

        /** `teleport_duration` for followed anchors: smooths the per-tick move. */
        public const val FOLLOW_TELEPORT_TICKS: Int = 2

        /** Client maximum for `teleport_duration`. */
        public const val MAX_TELEPORT_TICKS: Int = 59

        /** Vanilla `view_range` of a display that does not set one. */
        public const val DEFAULT_VIEW_RANGE: Float = 1.0f

        /**
         * Effect age at which a staged rig is revealed (it spawns hidden at age 0). 1 = the earliest a rig can move: the reveal
         * bundle carries the key 0 -> 1 update, so only key 0 must equal the held pose (bastion S1 r24 coil). r18 chose 2 because a
         * display spawned one server tick earlier may not have had a client tick yet; r24 re-measured 1 at 60 fps (2 side + 2 opponent): one weapon in every frame, no empty or doubled reveal frame.
         */
        public const val REVEAL_AGE: Int = 1
    }
}
