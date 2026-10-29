package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.SpawnRole

/** Centre of [team]'s base: the centroid of its TEAM spawn points (the Norma check zone is built around it). */
internal fun ScenarioScope.baseCentre(matchId: MatchId, team: TeamId): Vec3 {
    val stageId = sim.services.getOrThrow<IMatchRepository>().findById(matchId)!!.stageId
    val pts = sim.services.getOrThrow<IStageRegistry>().find(stageId)!!.spawnPoints.filter { it.role == SpawnRole.TEAM && it.team == team }
    return Vec3(pts.map { it.position.x }.average(), pts.map { it.position.y }.average(), pts.map { it.position.z }.average())
}

/** Walks [who] to the centre of [team]'s base. */
internal fun ScenarioScope.walkToBase(matchId: MatchId, who: SimEntity, team: TeamId) {
    who.moveTo(baseCentre(matchId, team))
    advance(2)
}

/** Walks [who] home and runs the Norma check (`/astera norma check`); returns whether the command succeeded. */
internal fun ScenarioScope.normaCheckAtHome(matchId: MatchId, who: SimEntity): Boolean {
    walkToBase(matchId, who, who.team!!)
    val ok = sim.command(who.name, "norma check").succeeded
    advance(2)
    return ok
}
