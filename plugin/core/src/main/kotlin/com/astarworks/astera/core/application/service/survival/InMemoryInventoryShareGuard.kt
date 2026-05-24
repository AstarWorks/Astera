package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IInventoryShareGuard
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.survival.GameModeContext
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IInventoryShareGuard] (Wave 5pve-survival / ADR-0107).
 *
 * Default rule matrix (user-confirmed 2026-05-20):
 *
 *  - Same mode → always carriable.
 *  - SurvivalIsland → PvpMatch → DENY by default (no gathering tools /
 *    crafted gear into PvP).
 *  - PveWorld → PvpMatch → DENY by default (PvE loot not allowed in PvP).
 *  - Every other transition → ALLOW (currency / cosmetics / lobby items).
 *
 * Per-item overrides live in [denyRules]; calling [deny] adds a rule and
 * [clearDeny] removes one. The wider "currency stays carriable / items do
 * not" policy is enforced by the platform inventory adapter using item
 * tags — this guard is the policy interface so the adapter has one
 * consultation point.
 */
public class InMemoryInventoryShareGuard : IInventoryShareGuard {

    private data class Edge(val from: GameModeContext, val to: GameModeContext)

    private val denyRules: ConcurrentHashMap<Edge, MutableSet<ItemId>> = ConcurrentHashMap()

    override fun canCarryAcrossMode(
        itemId: ItemId,
        from: GameModeContext,
        to: GameModeContext,
    ): Boolean {
        if (from == to) return true
        // Per-item explicit deny wins.
        denyRules[Edge(from, to)]?.let { items ->
            if (itemId in items) return false
        }
        // Default policy: items never cross into PvP from survival / pve;
        // everything else allowed.
        return when {
            to == GameModeContext.PvpMatch &&
                (from == GameModeContext.SurvivalIsland || from == GameModeContext.PveWorld) -> false
            else -> true
        }
    }

    override fun deny(itemId: ItemId, from: GameModeContext, to: GameModeContext) {
        require(from != to) { "Cannot deny same-mode carry ($from -> $to)" }
        denyRules.computeIfAbsent(Edge(from, to)) { mutableSetOf() }.add(itemId)
    }

    override fun clearDeny(itemId: ItemId, from: GameModeContext, to: GameModeContext): Boolean {
        val set = denyRules[Edge(from, to)] ?: return false
        return set.remove(itemId)
    }
}
