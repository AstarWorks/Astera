package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Mining-progress lifecycle: player successfully broke the block (Wave 5animation, ADR-0113).
 *
 * Distinct from [BlockBrokenBySkill] (skill-induced break) — this fires
 * for the manual-mining path. The block-side [BlockBroken] event still
 * fires from the durability gateway; this event covers the player-facing
 * lifecycle: "the mining session this player started completed
 * successfully".
 *
 * @property playerId the miner.
 * @property world world id.
 * @property blockPosition block that broke.
 * @property elapsedTicks total ticks the mining session took.
 * @property atTick server tick at completion time.
 */
public data class MiningCompleted(
    public val playerId: PlayerId,
    public val world: WorldId,
    public val blockPosition: Vec3,
    public val elapsedTicks: Long,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(elapsedTicks >= 0L) {
            "MiningCompleted.elapsedTicks must be >= 0, was $elapsedTicks"
        }
    }
}
