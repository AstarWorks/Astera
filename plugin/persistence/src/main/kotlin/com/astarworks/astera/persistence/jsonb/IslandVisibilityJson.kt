package com.astarworks.astera.persistence.jsonb

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * JSONB payload for `party_islands.visibility_payload` (Wave 5pve-survival /
 * ADR-0107).
 *
 * Only populated when `visibility_kind == FriendsAllowed`; the column is
 * `NULL` for `PartyOnly` / `Public`. Stored as a stringified UUID array so
 * the row stays human-debuggable (psql friendly).
 */
@Serializable
public data class IslandVisibilityJson(
    public val invitedFriends: List<String> = emptyList(),
) {
    public fun invitedFriendsAsUuids(): Set<UUID> =
        invitedFriends.mapTo(mutableSetOf()) { UUID.fromString(it) }

    public companion object {
        public fun fromUuids(uuids: Collection<UUID>): IslandVisibilityJson =
            IslandVisibilityJson(invitedFriends = uuids.map(UUID::toString))
    }
}

/**
 * JSONB payload for `island_plots.build_allowed` (Wave 5pve-survival /
 * ADR-0107). UUID-as-string array, same rationale as
 * [IslandVisibilityJson].
 */
@Serializable
public data class IslandPlotAllowedJson(
    public val playerIds: List<String> = emptyList(),
) {
    public fun playerIdsAsUuids(): Set<UUID> =
        playerIds.mapTo(mutableSetOf()) { UUID.fromString(it) }

    public companion object {
        public fun fromUuids(uuids: Collection<UUID>): IslandPlotAllowedJson =
            IslandPlotAllowedJson(playerIds = uuids.map(UUID::toString))
    }
}
