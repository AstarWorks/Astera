package com.astarworks.astera.match.domain.star

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.domain.generator.GeneratorId

/**
 * Stars lying on the map: a generator pop ([generatorId] set) or a kill burst (`null`). The first participant who
 * walks within [pickupRadius] takes all [amount]. Match-scoped: gone at match end.
 */
public data class StarDrop(
    val id: Long,
    val matchId: MatchId,
    val world: WorldId,
    val position: Vec3,
    val amount: Int,
    val pickupRadius: Double,
    val generatorId: GeneratorId?,
    /** A kill burst cannot be taken back by its victim until [deniedUntilMillis] (wall clock). */
    val deniedTo: com.astarworks.astera.core.domain.player.PlayerId? = null,
    val deniedUntilMillis: Long = 0L,
    /** Handle of the world thing showing this drop ([com.astarworks.astera.match.application.port.outbound.IStarDropWorld]); null in headless hosts. */
    val entity: String? = null,
    /** Not takeable before this wall-clock time (lets a kill burst be seen flying out). */
    val readyAtMillis: Long = 0L,
)
