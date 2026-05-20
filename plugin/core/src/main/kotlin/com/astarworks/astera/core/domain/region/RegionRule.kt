package com.astarworks.astera.core.domain.region

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * A single permission rule attached to a [com.astarworks.astera.core.domain.stage.Region]
 * via [RegionId].
 *
 * The rule's [scope] decides whether the [permission] is *granted* or
 * *denied* when it matches. The [players] field gates *whose* action the
 * rule applies to:
 *
 * - `null` → applies to everyone (the everyone-row in V25 `region_permission`).
 * - non-null set → applies only to the listed players.
 *
 * Resolution order (mirrored by the Postgres lookup query in
 * `PostgresRegionPermissionService`):
 *
 * 1. Player-specific rule wins over everyone rule.
 * 2. Within the same scope, "deny" wins over "allow" (defence-in-depth;
 *    callers should not author conflicting rules but the resolver is
 *    deterministic when they do).
 * 3. Absent any rule, default is "allow" (Phase 1 PoC posture — explicit
 *    deny is required to block actions; Wave 5o's spawn-protection layer
 *    sits on top of this for per-player overrides).
 *
 * Wave 5n (ADR-0080).
 */
public data class RegionRule(
    val regionId: RegionId,
    val permission: RegionPermission,
    val scope: Scope,
    val players: Set<PlayerId>? = null,
) {

    /**
     * Whether the rule grants or denies [permission] when it matches.
     */
    public enum class Scope { ALLOW, DENY }

    /**
     * Returns `true` if this rule applies to [playerId]. A `null` [players]
     * means "everyone", which always matches.
     */
    public fun appliesTo(playerId: PlayerId): Boolean =
        players == null || playerId in players
}
