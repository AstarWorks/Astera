package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Floating text labels over the structures of a match ("your base", "stand here to capture", a pillar's HP). A label
 * is per viewer group: the same spot can read "your base" for one team and "enemy base" for the other, in each
 * viewer's language, so the caller hands in the rendered text with the viewers who see it. Everything is per match
 * and torn down by [clearMatch]; the hosts own the entities (Paper: one TextDisplay per text, shown to its viewers only).
 */
public interface IWorldLabels {
    /**
     * Places or updates label [key] of [match] at [at]. [variants] maps each rendered MiniMessage text to the players
     * who read it. Calling it again with the same key moves the label, swaps changed texts and drops texts that are
     * no longer listed.
     */
    public fun show(match: MatchId, key: String, world: WorldId, at: Vec3, variants: Map<String, Set<PlayerId>>)

    /** Removes label [key] of [match]. Idempotent. */
    public fun remove(match: MatchId, key: String)

    /** Match over: removes every label of [match]. */
    public fun clearMatch(match: MatchId)
}
