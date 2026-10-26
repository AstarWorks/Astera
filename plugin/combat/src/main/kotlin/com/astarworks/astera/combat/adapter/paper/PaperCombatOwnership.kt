package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.ICombatOwnership
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Bukkit
import kotlin.time.Duration

public class PaperCombatOwnership(private val scheduler: IScheduler, private val grid: CombatSpatialGrid) : ICombatOwnership {
    private val log = java.util.logging.Logger.getLogger(javaClass.name)
    init {
        ExecutionCosts.increment("combat_cross_region_hops", 0)
        ExecutionCosts.increment("combat_ownership_violations", 0)
    }

    override fun deferIfForeign(target: PlayerId, action: () -> Unit): Boolean {
        val entity = grid.entity(target.uuid) ?: return false
        if (Bukkit.isOwnedByCurrentRegion(entity)) return false
        ExecutionCosts.increment("combat_cross_region_hops")
        log.fine("Cross-region combat hop target=${target.uuid} tick=${scheduler.currentTick()}")
        scheduler.run(ExecutionScope.Entity(target.uuid), Duration.ZERO) {
            if (checkOwned(target)) action()
        }
        return true
    }

    override fun checkOwned(target: PlayerId): Boolean {
        val entity = grid.entity(target.uuid) ?: return true
        if (Bukkit.isOwnedByCurrentRegion(entity)) return true
        ExecutionCosts.increment("combat_ownership_violations")
        log.severe("Combat accessed foreign target ${target.uuid}; skipping target")
        return false
    }
}
