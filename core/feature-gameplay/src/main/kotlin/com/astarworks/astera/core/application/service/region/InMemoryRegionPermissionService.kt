package com.astarworks.astera.core.application.service.region

import com.astarworks.astera.core.application.port.outbound.IRegionPermissionService
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.region.RegionPermission
import com.astarworks.astera.core.domain.region.RegionRule
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IRegionPermissionService] used at priority 0 until the Postgres
 * overlay (V25 migration) registers at priority 100.
 *
 * Rules are stored in a flat `ConcurrentHashMap<RegionId, MutableList<RegionRule>>`
 * keyed by region. The resolver:
 *
 * 1. Asks [regionIndex] for every region at the position.
 * 2. For each region, walks its rules and applies the precedence in
 *    [RegionRule] kdoc (player-specific > everyone; deny > allow within the
 *    same scope).
 * 3. Falls back to `true` (default-allow) if nothing matches.
 *
 * Idempotent allow/deny: re-adding the same (region, permission, players)
 * tuple replaces the existing rule rather than creating a duplicate.
 *
 * Wave 5n (ADR-0080).
 */
public class InMemoryRegionPermissionService(
    private val regionIndex: IStageRegionIndex,
) : IRegionPermissionService {

    private val rulesByRegion: MutableMap<RegionId, MutableList<RegionRule>> = ConcurrentHashMap()

    override fun check(
        playerId: PlayerId,
        position: Vec3,
        world: WorldId,
        permission: RegionPermission,
    ): Boolean {
        val regions = regionIndex.regionsAt(world, position)
        if (regions.isEmpty()) return true

        // Find the most specific matching rule per region; aggregate so any
        // matching DENY blocks the action.
        var sawAllow = false
        var sawDeny = false
        for (regionId in regions) {
            val ruleList = rulesByRegion[regionId] ?: continue
            val matched = pickRule(ruleList, permission, playerId) ?: continue
            when (matched.scope) {
                RegionRule.Scope.ALLOW -> sawAllow = true
                RegionRule.Scope.DENY -> sawDeny = true
            }
        }

        // deny beats allow (defence-in-depth); if nothing matched at all, default allow.
        if (sawDeny) return false
        if (sawAllow) return true
        return true
    }

    private fun pickRule(
        rules: List<RegionRule>,
        permission: RegionPermission,
        playerId: PlayerId,
    ): RegionRule? {
        val playerRule = rules.firstOrNull {
            it.permission == permission && it.players != null && playerId in it.players
        }
        if (playerRule != null) return playerRule
        return rules.firstOrNull { it.permission == permission && it.players == null }
    }

    override fun allow(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>?,
    ) {
        upsert(RegionRule(regionId, permission, RegionRule.Scope.ALLOW, players))
    }

    override fun deny(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>?,
    ) {
        upsert(RegionRule(regionId, permission, RegionRule.Scope.DENY, players))
    }

    private fun upsert(rule: RegionRule) {
        val list = rulesByRegion.computeIfAbsent(rule.regionId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }
        synchronized(list) {
            list.removeAll { existing ->
                existing.permission == rule.permission && existing.players == rule.players
            }
            list += rule
        }
    }

    override fun clear(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>?,
    ): Boolean {
        val list = rulesByRegion[regionId] ?: return false
        return synchronized(list) {
            list.removeAll { existing ->
                existing.permission == permission && existing.players == players
            }
        }
    }

    override fun rules(regionId: RegionId): List<RegionRule> {
        val list = rulesByRegion[regionId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }
}
