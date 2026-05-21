package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.anticheat.IExternalAnticheatBridge
import com.astarworks.astera.core.domain.anticheat.AnticheatAction
import com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion
import com.astarworks.astera.core.domain.event.AnticheatActionTaken
import com.astarworks.astera.core.domain.event.AnticheatFlagRaised
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Glues local validators, the external bridge, the permission service
 * (Wave 5t role check), and the domain event bus together.
 *
 * Typical call flow from Wave 5a / 5n adapters:
 *
 * ```kotlin
 * val verdict = movementValidator.validate(playerId, from, to, deltaTicks)
 * when (verdict) {
 *     MovementValidation.Ok -> {}
 *     is MovementValidation.Suspicious -> orchestrator.report(
 *         playerId, AnticheatSuspicion.Movement(verdict.reason), atTick,
 *     )
 *     is MovementValidation.Reject -> {
 *         orchestrator.report(playerId, AnticheatSuspicion.Movement(...), atTick)
 *         // caller cancels the event
 *     }
 * }
 * ```
 *
 * The permission check is the **escape hatch**: admins or GMs holding
 * [BYPASS_PERMISSION] skip both flag emission *and* action execution
 * (Wave 5t two-tier role integration, ADR-0086 + ADR-0083).
 */
public class AnticheatOrchestrator(
    private val externalBridge: IExternalAnticheatBridge,
    private val permissionService: IPermissionService,
    private val eventBus: IDomainEventBus,
    private val policy: AnticheatActionPolicy = DefaultAnticheatActionPolicy,
) {

    /**
     * Record a suspicion. Returns the action that was *decided* (and
     * already emitted as an [AnticheatActionTaken] event when non-null).
     * Returns null when the player is bypassed.
     */
    public fun report(
        playerId: PlayerId,
        suspicion: AnticheatSuspicion,
        atTick: Long,
    ): AnticheatAction? {
        if (isBypassed(playerId)) return null

        eventBus.publish(AnticheatFlagRaised(playerId, suspicion, atTick))
        externalBridge.reportSuspicion(playerId, suspicion)
        val action = policy.decide(suspicion, externalBridge.queryFlagLevel(playerId))
        eventBus.publish(AnticheatActionTaken(playerId, action, atTick))
        return action
    }

    public fun isBypassed(playerId: PlayerId): Boolean =
        permissionService.check(playerId, BYPASS_PERMISSION)

    public companion object {
        /** Holders skip all anti-cheat surfaces (Wave 5t role tree). */
        public val BYPASS_PERMISSION: Permission = Permission("astera.anticheat.bypass")
    }
}

/**
 * Decides which [AnticheatAction] a flag should trigger. Kept as a
 * separate interface so a server admin can wire a custom policy without
 * subclassing the orchestrator.
 */
public fun interface AnticheatActionPolicy {
    public fun decide(suspicion: AnticheatSuspicion, externalFlagLevel: Int): AnticheatAction
}

/**
 * Stock policy: severity-based.
 *
 * - external flag level ≥ 8 OR `CustomFlag.severity ≥ 8` → [AnticheatAction.Ban]
 * - external flag level ≥ 4 OR `CustomFlag.severity ≥ 4` → [AnticheatAction.Kick]
 * - Astera-local rate suspicion → [AnticheatAction.Notify]
 * - everything else → [AnticheatAction.Quiet]
 */
public object DefaultAnticheatActionPolicy : AnticheatActionPolicy {
    override fun decide(suspicion: AnticheatSuspicion, externalFlagLevel: Int): AnticheatAction {
        val customSeverity = (suspicion as? AnticheatSuspicion.CustomFlag)?.severity ?: 0
        val severity = maxOf(externalFlagLevel, customSeverity)
        return when {
            severity >= 8 -> AnticheatAction.Ban
            severity >= 4 -> AnticheatAction.Kick
            suspicion is AnticheatSuspicion.Movement -> AnticheatAction.Notify
            suspicion is AnticheatSuspicion.DamageRate -> AnticheatAction.Notify
            suspicion is AnticheatSuspicion.Interact -> AnticheatAction.Notify
            else -> AnticheatAction.Quiet
        }
    }
}
