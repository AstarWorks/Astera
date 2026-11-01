package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.IObjectiveStateQuery
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveKit
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveSnapshot
import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.domain.mode.GameModeId

internal fun ScenarioScope.matchStage(matchId: MatchId): Stage {
    val match = checkNotNull(sim.services.getOrThrow<IMatchRepository>().findById(matchId))
    return checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(match.stageId))
}

/** Red breaks the shield, right-clicks the TNT, holds each blue checkpoint and delivers the knockout. */
internal fun ScenarioScope.deliverTnt(who: SimEntity, matchId: MatchId) {
    val stage = matchStage(matchId)
    val query = sim.services.getOrThrow<IGameModeFrameworkRegistry>().get(GameModeId("carry-tnt")) as IObjectiveStateQuery
    who.moveTo(ObjectiveKit.centreOf(checkNotNull(stage.region("tnt-spawn")), 1.0))
    check(advanceUntil(20 * 40) { (query.objectiveSnapshot(matchId) as ObjectiveSnapshot.Rainmaker).shieldHp <= 0.0 })
    clickNearestMarker(who, matchId)
    advance(2)
    check(sim.objectiveStage.carrying(who.id) != null)
    for (checkpoint in stage.regionsWithPrefix("checkpoint-blue-").sortedBy { it.name }) {
        who.moveTo(ObjectiveKit.centreOf(checkpoint, 1.0))
        advance(90)
    }
    who.moveTo(ObjectiveKit.centreOf(checkNotNull(stage.region("deliver-blue")), 1.0))
    advance(80)
}
