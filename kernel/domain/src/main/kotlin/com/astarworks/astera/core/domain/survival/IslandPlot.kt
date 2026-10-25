package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region

/**
 * Per-member build territory on a [PartyIsland] (Wave 5pve-survival /
 * ADR-0107).
 *
 * Reuses [Region] (axis-aligned cuboid from `domain/stage/`) for the bounds
 * — every spatial-index user in Astera already speaks `Region`, so the
 * Wave 5n grief-protection pipeline can register `IRegionPermissionService`
 * deny rules against the same shape without translation.
 *
 * # Build allow-list
 *
 * [buildAllowed] is owner + invited co-builders. The owner is implicit
 * (always in [buildAllowed]) but stored explicitly so the use case can
 * answer `canBuild` with a single `O(1)` set lookup instead of `owner ==
 * id || id in invited`.
 *
 * # Why no `Cuboid` value type
 *
 * The idea md proposes a fresh `Cuboid`, but Core already has [Region]
 * with identical semantics (`min`/`max` axis-aligned, inclusive bounds,
 * `contains(point)`, `volume`). Adding a parallel value type would force
 * cross-translation in every region-permission integration and violate the
 * "one canonical shape per concept" Konsist would otherwise flag in a
 * future audit.
 */
public data class IslandPlot(
    val id: PlotId,
    val islandId: IslandId,
    val ownerId: PlayerId,
    val regionBounds: Region,
    val buildAllowed: Set<PlayerId>,
) {
    init {
        require(ownerId in buildAllowed) {
            "IslandPlot.ownerId ($ownerId) must be in buildAllowed; owner is always implicit-allowed"
        }
    }

    /** True if [playerId] may modify blocks inside this plot. */
    public fun canBuild(playerId: PlayerId): Boolean = playerId in buildAllowed

    /**
     * Adds [playerId] to [buildAllowed]. Idempotent — re-adding is a no-op.
     * The owner cannot be removed (constructor invariant).
     */
    public fun grantBuild(playerId: PlayerId): IslandPlot =
        copy(buildAllowed = buildAllowed + playerId)

    /**
     * Removes [playerId] from [buildAllowed]. Fails-fast if [playerId] is
     * the owner — the owner cannot be locked out of their own plot.
     */
    public fun revokeBuild(playerId: PlayerId): IslandPlot {
        require(playerId != ownerId) { "Cannot revoke build for owner ($ownerId)" }
        return copy(buildAllowed = buildAllowed - playerId)
    }
}
