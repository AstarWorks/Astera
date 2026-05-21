package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.region.RegionPermission
import com.astarworks.astera.core.domain.region.RegionRule
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: per-region permission service. The grief-prevention primary
 * mechanism per `.claude/plans/idea-anti-grief-policy.md` (Wave 5n / ADR-0092).
 *
 * Combines two pieces of state:
 * - The set of [RegionRule]s authored against each [RegionId] (via
 *   [allow] / [deny] / [rules]).
 * - The position → region mapping (resolved via the injected
 *   [IStageRegionIndex] in production; the in-memory impl ignores
 *   position and key everything by RegionId for unit tests).
 *
 * The check API is intentionally synchronous and side-effect-free; callers
 * typically gate Bukkit event handlers (`BlockPlaceEvent`,
 * `EntityDamageByEntityEvent`, …) so blocking on I/O would stall the main
 * thread.
 *
 * Default policy: **allow** when no rule matches. Explicit `deny` rules are
 * required to block actions. This matches RTM behaviour and matches the
 * Phase 1 PoC anti-grief plan (regions opt *in* to protection by adding
 * deny rules; vanilla blocks stay buildable everywhere else).
 */
public interface IRegionPermissionService {

    /**
     * Resolve whether [playerId] is permitted to perform [permission] at
     * [position] in [world]. Returns `true` if allowed, `false` if denied.
     *
     * Resolution:
     * 1. Find every region at [position] (via the spatial index in prod).
     * 2. Collect every [RegionRule] attached to those regions.
     * 3. Player-specific rules win over everyone rules; within the same
     *    scope, `deny` wins over `allow` (see [RegionRule] kdoc).
     * 4. No matching rule → returns `true` (default-allow).
     */
    public fun check(
        playerId: PlayerId,
        position: Vec3,
        world: WorldId,
        permission: RegionPermission,
    ): Boolean

    /**
     * Register an `allow` rule. `null` [players] means "everyone".
     *
     * Idempotent: re-adding the same (region, permission, players) tuple
     * replaces the existing rule's scope rather than creating a duplicate.
     */
    public fun allow(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>? = null,
    )

    /**
     * Register a `deny` rule. `null` [players] means "everyone".
     *
     * Idempotent (see [allow]).
     */
    public fun deny(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>? = null,
    )

    /**
     * Remove every rule matching (region, permission, players). Pass
     * `players = null` to remove the everyone-rule specifically. Returns
     * `true` if anything was removed.
     */
    public fun clear(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>? = null,
    ): Boolean

    /**
     * Returns every rule currently attached to [regionId], in undefined
     * order. Mostly used by `/astera-region rules` admin commands and by
     * the priority-100 Postgres overlay's bootstrap.
     */
    public fun rules(regionId: RegionId): List<RegionRule>
}
