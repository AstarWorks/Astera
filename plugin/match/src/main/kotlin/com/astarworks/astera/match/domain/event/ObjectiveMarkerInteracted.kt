package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A player right-clicked a loose objective marker (the TNT on its pedestal or on the ground, a mini-TNT clam).
 * `key` is the marker key the mode passed to `IObjectiveStage.showMarker`; the mode decides what the click means.
 */
public data class ObjectiveMarkerInteracted(
    val matchId: MatchId,
    val playerId: PlayerId,
    val key: String,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
