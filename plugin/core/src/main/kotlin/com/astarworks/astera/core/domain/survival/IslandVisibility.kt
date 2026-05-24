package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Who is allowed to visit a [PartyIsland] (Wave 5pve-survival / ADR-0107).
 *
 * `PartyOnly` is the default and the only mode the foundation wave exercises
 * end-to-end; `FriendsAllowed` and `Public` are recorded here so the sealed
 * hierarchy is closed in Core today and Phase 4 visitor flows do not need to
 * break the shape. The `IPartyIslandManager.visit` use case fans the policy
 * out into either a roster lookup (`PartyOnly` / `FriendsAllowed`) or an
 * unconditional accept (`Public`).
 *
 * # Why a sealed interface (not enum)
 *
 * `FriendsAllowed` carries a per-island invite set; an enum cannot hold
 * payload. Conflating Public / PartyOnly into "no payload" enum constants
 * and stashing the friend set elsewhere would split policy from data and
 * tempt callers to ignore the visitor whitelist.
 */
public sealed interface IslandVisibility {

    /** Only [PartyIsland.partyId] members can load / visit the island. */
    public data object PartyOnly : IslandVisibility

    /**
     * Party members + an explicit friend allow-list may visit. The allow-list
     * is keyed on [PlayerId]; revocation is `copy(invitedFriends = ... - id)`.
     */
    public data class FriendsAllowed(val invitedFriends: Set<PlayerId>) : IslandVisibility {
        init {
            // Defensive: empty FriendsAllowed is meaningless (same as
            // PartyOnly); force the caller to be explicit.
            require(invitedFriends.isNotEmpty()) {
                "IslandVisibility.FriendsAllowed.invitedFriends must not be empty; use PartyOnly instead"
            }
        }
    }

    /** Anyone online can visit (read-only — build rules still apply per-plot). */
    public data object Public : IslandVisibility
}
