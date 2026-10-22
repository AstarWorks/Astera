package com.astarworks.astera.combat.application.admin

import com.astarworks.astera.combat.application.port.outbound.IGodModeService
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.resource.ResourceType

/** Why an admin combat tool did not apply. */
public sealed class AdminToolError {
    /** The player has no Astera HP container (offline / never joined). */
    public data class NoHealth(val playerId: PlayerId) : AdminToolError()

    /** The tool's backing service is not wired on this host. */
    public data class Unavailable(val tool: String) : AdminToolError()

    /** The player has no resource pool (no weapon held yet). */
    public data class NoResourcePool(val playerId: PlayerId) : AdminToolError()
}

/**
 * Admin-panel combat tools (F1-D, ADR-0183): heal to full on the **Astera HP
 * container** (not vanilla health), clear statuses, clear cooldowns, refill
 * ammo / mana / stamina, toggle god mode. Each operates on any player so the
 * same use case serves "self tools" and "per-player actions".
 */
public class AdminCombatToolsUseCase(
    private val health: IPlayerHealthGateway,
    private val statuses: IStatusEffectContainer?,
    private val cooldowns: ICooldownTracker?,
    private val resources: IResourcePoolService?,
    private val godMode: IGodModeService,
) {

    /** Restore [target] to max Astera HP. */
    public fun healFull(target: PlayerId): Result<Unit, AdminToolError> {
        val max = health.getMaxHealth(target) ?: return Result.failure(AdminToolError.NoHealth(target))
        return when (health.setHealth(target, max, HealthChangeSource.AdminCommand)) {
            is Result.Success -> Result.success(Unit)
            is Result.Failure -> Result.failure(AdminToolError.NoHealth(target))
        }
    }

    public fun clearStatuses(target: PlayerId): Result<Unit, AdminToolError> {
        val container = statuses ?: return Result.failure(AdminToolError.Unavailable("statuses"))
        container.clear(target)
        return Result.success(Unit)
    }

    /** Reset every active cooldown; returns how many were cleared. */
    public fun clearCooldowns(target: PlayerId): Result<Int, AdminToolError> {
        val tracker = cooldowns ?: return Result.failure(AdminToolError.Unavailable("cooldowns"))
        val keys = tracker.activeKeysFor(target)
        keys.forEach { tracker.reset(target, it) }
        return Result.success(keys.size)
    }

    /** Fill ammo, mana and stamina to the cap of the held weapon. */
    public fun refillResources(target: PlayerId): Result<Unit, AdminToolError> {
        val pools = resources ?: return Result.failure(AdminToolError.Unavailable("resources"))
        if (pools.currentPool(target) == null) return Result.failure(AdminToolError.NoResourcePool(target))
        for (type in ResourceType.all) {
            pools.applyRegen(target, type, pools.capFor(target, type).maxPool)
        }
        return Result.success(Unit)
    }

    /** Flip god mode; returns the new state. */
    public fun toggleGod(target: PlayerId): Boolean = godMode.toggle(target)
}
