package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.ICombatOwnership
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Bukkit
import org.slf4j.LoggerFactory
import kotlin.time.Duration

public class PaperCombatOwnership(private val scheduler: IScheduler, private val grid: CombatSpatialGrid) : ICombatOwnership {
    private val log = LoggerFactory.getLogger(javaClass)
    init { ExecutionCosts.increment("combat_cross_region_hops", 0) }

    override fun deferIfForeign(target: PlayerId, action: () -> Unit): Boolean {
        val entity = grid.entity(target.uuid) ?: return false
        if (Bukkit.isOwnedByCurrentRegion(entity)) return false
        ExecutionCosts.increment("combat_cross_region_hops")
        log.info("Cross-region combat hop target={} tick={}", target.uuid, scheduler.currentTick())
        scheduler.run(ExecutionScope.Entity(target.uuid), Duration.ZERO) {
            assertOwned(target)
            action()
        }
        return true
    }

    override fun assertOwned(target: PlayerId) {
        val entity = grid.entity(target.uuid) ?: return
        check(Bukkit.isOwnedByCurrentRegion(entity)) { "Combat accessed foreign target ${target.uuid}" }
    }
}
