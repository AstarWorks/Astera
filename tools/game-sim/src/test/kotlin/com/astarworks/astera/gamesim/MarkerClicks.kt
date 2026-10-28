package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.event.ObjectiveMarkerInteracted
import kotlin.math.hypot

/**
 * Simulates the right-click a bot makes on a loose objective marker: publishes the event the Paper stage raises for the
 * loose marker closest to [who] (no-op when none is loose).
 */
internal fun ScenarioScope.clickNearestMarker(who: SimEntity, matchId: MatchId) {
    val nearest = sim.objectiveStage.markers(matchId).filterValues { it.look.loose }
        .minByOrNull { hypot(it.value.position.x - who.position.x, it.value.position.z - who.position.z) } ?: return
    sim.eventBus.publish(ObjectiveMarkerInteracted(matchId, who.id, nearest.key))
}
