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
)
