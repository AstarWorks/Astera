package com.astarworks.astera.match.domain.star

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.domain.generator.GeneratorId

/**
 * Stars lying on the map: a generator pop ([generatorId] set) or a kill burst (`null`). The first participant who
 * picks the item up takes all [amount]. [pickupRadius] is authored on the generator but unused: the host's item pickup range decides. Match-scoped: gone at match end.
 */
public data class StarDrop(
    val id: Long,
    val matchId: MatchId,
    val world: WorldId,
    val position: Vec3,
    val amount: Int,
    val pickupRadius: Double,
    val generatorId: GeneratorId?,
    /** A kill burst cannot be taken back by its victim until scheduler tick [deniedUntilTick]. */
    val deniedTo: com.astarworks.astera.core.domain.player.PlayerId? = null,
    val deniedUntilTick: Long = 0L,
    /** Handle of the world thing showing this drop ([com.astarworks.astera.match.application.port.outbound.IStarDropWorld]); null in headless hosts. */
    val entity: String? = null,
    /** The host holds the pickup back until this scheduler tick (a kill burst is seen flying out): becomes the item entity's pickup delay. */
    val readyAtTick: Long = 0L,
)
