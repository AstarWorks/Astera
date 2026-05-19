package com.astarworks.astera.core.domain.hologram

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Who can see this hologram (Wave 5h, ADR-0074 §1).
 *
 * Paper adapter (Wave 6b) consults this on `spawn` to decide which players
 * receive the entity-create packet.
 *
 * Sealed: adding a new variant (e.g. `PerPermission(node: String)`) is a
 * deliberate change.
 */
public sealed interface HologramVisibilityScope {
    /** Visible to every online player. */
    public data object Everyone : HologramVisibilityScope

    /** Visible only to the listed players. */
    public data class PerPlayer(public val playerIds: Set<PlayerId>) : HologramVisibilityScope

    /** Visible only to members of [teamId] in [matchId]. */
    public data class PerTeam(
        public val matchId: MatchId,
        public val teamId: com.astarworks.astera.core.domain.match.TeamId,
    ) : HologramVisibilityScope
}
