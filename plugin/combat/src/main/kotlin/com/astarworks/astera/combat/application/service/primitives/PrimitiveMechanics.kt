package com.astarworks.astera.combat.application.service.primitives

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.service.TargeterResolver
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ITeamColorLookup
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import org.slf4j.Logger

/**
 * Everything a vfx-primitive handler may need, resolved once by [MechanicDispatcher]. Frozen after Phase A of the
 * vfx-primitives track: a handler that needs another collaborator reports it instead of editing this file.
 * Lazy ports (`() -> X?`) resolve per call because their overlays register after Combat enables.
 */
internal class PrimitiveDeps(
    /** Value resolution: `host.resolveDouble / resolveInt / resolveBool / resolveVec3`. */
    val host: MechanicDispatcher,
    val targeters: TargeterResolver,
    val scheduler: IScheduler,
    val worldGateway: IWorldGateway,
    val feedback: IPlayerFeedbackGateway?,
    val displays: IDisplayEntityGateway?,
    val entityEffects: IEntityEffectsGateway?,
    val soundScheduler: ISoundScheduler?,
    val soundRegistry: ISoundRegistry?,
    val playerSound: () -> IPlayerSoundGateway?,
    val teamColors: () -> ITeamColorLookup?,
    val teamLookup: IMatchTeamLookup?,
    val poseLookup: IPlayerPoseLookup?,
    val log: Logger,
) {
    /** Stub body helper: the primitive is declared but its territory has not landed yet. */
    fun notImplemented(mechanic: String) {
        log.warn("{} mechanic dropped: handler not implemented yet", mechanic)
    }
}

/** Executes one mechanic variant. One implementation per file, each owned by exactly one territory. */
internal fun interface PrimitiveHandler<M : Mechanic> {
    fun handle(mechanic: M, ctx: MechanicContext)
}

/**
 * Hook for the generic VFX primitives (ADR-0290 / ADR-0291). [MechanicDispatcher] routes `stop_sound`, `screen_veil` and
 * `telegraph` here; each handler lives in its own file so territories never touch the dispatcher:
 *  - [StopSoundHandler] (T1 sound)
 *  - [TelegraphHandler], [ScreenVeilHandler] (T5)
 * This aggregator is frozen after Phase A.
 */
internal class PrimitiveMechanics(deps: PrimitiveDeps) {
    private val stopSound: PrimitiveHandler<Mechanic.StopSound> = StopSoundHandler(deps)
    private val telegraph: PrimitiveHandler<Mechanic.Telegraph> = TelegraphHandler(deps)
    private val screenVeil: PrimitiveHandler<Mechanic.ScreenVeil> = ScreenVeilHandler(deps)

    fun stopSound(mechanic: Mechanic.StopSound, ctx: MechanicContext): Unit = stopSound.handle(mechanic, ctx)
    fun telegraph(mechanic: Mechanic.Telegraph, ctx: MechanicContext): Unit = telegraph.handle(mechanic, ctx)
    fun screenVeil(mechanic: Mechanic.ScreenVeil, ctx: MechanicContext): Unit = screenVeil.handle(mechanic, ctx)
}
