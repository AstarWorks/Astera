package com.astarworks.astera.combat.domain.skill.expression

import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Runtime context handed to [ICelEvaluator.evaluate].
 *
 * Wave 5e ADR-0058 made `IAttributeContainer` the universal attribute
 * surface — every CEL lookup like `player.attr['stat.kills']` goes through
 * this. Phase 6 real eval will compile the CEL syntax against this
 * structure; Phase 1 Stub doesn't read any of these fields.
 */
public data class CelContext(
    public val player: PlayerData,
    public val target: PlayerData? = null,
    public val projectileMeta: Map<String, Any?> = emptyMap(),
) {
    public data class PlayerData(
        public val playerId: PlayerId,
        public val attr: IAttributeContainer,
    )
}
