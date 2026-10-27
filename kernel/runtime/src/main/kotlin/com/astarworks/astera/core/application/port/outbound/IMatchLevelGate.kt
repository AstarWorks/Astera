package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Norma rarity gate (ADR-0292): a weapon is usable once the holder's in-match Norma level reaches the level its
 * [Rarity] opens at (`rarity_levels` of `content/norma-layer/layer.yaml`).
 *
 * Lives in core so the Items plugin can ask without depending on the Match plugin (ADR-0022); Match implements it
 * with the player's in-match Norma level. Absent registration, or a player who is not in a Norma match, means no
 * gate (everything is usable).
 */
public interface IMatchLevelGate {
    /** The Norma level at which weapons of [rarity] open (1 = from the start); the layer defaults, outside any match. */
    public fun requiredLevel(rarity: Rarity): Int

    /**
     * `true` when [playerId] is below the level [rarity] opens at and the use must be refused. The implementation has
     * already given the feedback (action bar "Lv N で <rarity> 解放" plus a deny sound), so the caller just drops the action.
     */
    public fun denyIfLocked(playerId: PlayerId, rarity: Rarity): Boolean
}
