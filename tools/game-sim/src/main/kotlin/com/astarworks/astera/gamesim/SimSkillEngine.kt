package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.platform.wiring.CombatWiringResult
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.world.LedgerWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.gamesim.skillengine.GatewayRecorder
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * Skill engine v3 in the simulator: every [SkillCast] runs its compiled skill on one [TablePlayer], ticked once per scheduler
 * tick. The world ops (`break_area`, `place_shape`) run the production ledger over [SimBlockAccess], so a tool skill chips
 * cover blocks, plain blocks and ore nodes exactly like Paper. Visuals, particles, sound, damage and movement go to a
 * [GatewayRecorder] (they are recorded, not applied to the sim's entities).
 */
internal class SimSkillEngine(
    private val sim: SimWorld,
    private val scheduler: IScheduler,
    services: ServiceRegistry,
    private val eventBus: IDomainEventBus,
    private val combat: CombatWiringResult,
) {
    private class SimLedgerWorld(blocks: SimBlockAccess) : LedgerWorldGateway(blocks) {
        override fun explode(cast: CastRef, at: Vec3, power: Double, breakBlocks: Boolean, fire: Boolean) = Unit
        override fun lightning(cast: CastRef, at: Vec3, visualOnly: Boolean) = Unit
    }

    private val blockAccess = SimBlockAccess(sim, services)
    private val recorder = GatewayRecorder().also {
        it.faithfulPose = true
        it.blockRaycast = blockAccess::raycast
    }
    private val ledger = SimLedgerWorld(blockAccess)
    private val player = TablePlayer(
        recorder.visual, recorder.particles, recorder.sounds, recorder.gameplay, recorder.query, recorder.live, recorder,
        recorder.movement, recorder.items, ledger,
    ).also {
        it.hooks = recorder.hooks
        it.config = combat.engineConfig
    }
    private var sequence = 0L
    private var tickHandle: IScheduler.TaskHandle? = null
    private val subscriptions = ArrayList<IDomainEventBus.Subscription>()

    fun start() {
        runCatching { combat.reloadSkills() }
        tickHandle = scheduler.runRepeatingTicks(ExecutionScope.Global, 1L, 1L) {
            val now = scheduler.currentTick().toInt()
            recorder.tick = now
            player.tick(now)
            player.clearFaults()
        }
        subscriptions += eventBus.subscribe(MatchEnded::class) {
            player.live().forEach { player.cancel(it, com.astarworks.astera.core.domain.skillengine.table.CancelScope.Cast) }
            ledger.revertAll()
        }
        subscriptions += eventBus.subscribe(SkillCast::class) { onSkillCast(it) }
    }

    private fun onSkillCast(event: SkillCast) {
        val skill = combat.skillCatalog.get(event.skillId.value) ?: return
        val entity = sim.find(PlayerId(event.caster.uuid)) ?: return
        val d = entity.direction
        val yaw = atan2(-d.x, d.z) * 180.0 / PI
        val pitch = -asin(max(-1.0, min(1.0, d.y))) * 180.0 / PI
        recorder.casterYawDeg = yaw
        recorder.casterPitchDeg = pitch
        recorder.casterPosition = com.astarworks.astera.core.domain.skillengine.math.Vector3d(entity.position.x, entity.position.y + 1.0, entity.position.z)
        recorder.castItem = skill.def.item
        val request = CastRequest(
            skill = skill,
            caster = event.caster.uuid,
            world = entity.world,
            casterFeet = entity.position,
            casterYawDeg = yaw,
            casterPitchDeg = pitch,
            seed = (event.atTick xor event.caster.uuid.leastSignificantBits) xor (sequence++ * -0x61c8864680b583ebL),
            startTick = scheduler.currentTick().toInt(),
        )
        val started = player.start(request, skill)
        check(started is Result.Success) { "sim skill engine could not start ${event.skillId.value}: ${(started as Result.Failure).error}" }
    }

    fun shutdown() {
        tickHandle?.cancel()
        tickHandle = null
        subscriptions.forEach { runCatching { it.cancel() } }
        subscriptions.clear()
        runCatching { ledger.revertAll() }
    }
}
