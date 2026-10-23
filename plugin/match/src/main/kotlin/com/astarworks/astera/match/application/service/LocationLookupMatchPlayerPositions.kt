package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.PlayerLocation
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions

/** Host-neutral fallback fed by `PlayerMoved` (the simulator); Paper overrides it with a live read. */
public class LocationLookupMatchPlayerPositions(
    private val lookup: () -> IPlayerLocationLookup?,
) : IMatchPlayerPositions {
    override fun positionOf(playerId: PlayerId, includeDead: Boolean): PlayerLocation? = lookup()?.locate(playerId)
}
