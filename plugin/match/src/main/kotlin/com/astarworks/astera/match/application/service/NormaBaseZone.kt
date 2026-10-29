package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import kotlin.math.abs
import kotlin.math.hypot

/** Where a player stands relative to the team bases. */
public enum class BaseSite { OWN, ENEMY, NONE }

/**
 * The Norma check point: the base zone of a team = a disc of `norma_check_radius` blocks around the centroid of its
 * TEAM spawn points (stage YAML `spawn_points`), within [HEIGHT] blocks vertically. Spawn points exist on every PvP stage, so
 * every mode and every future stage has a base without extra authoring; the team shop NPC sits in the same area.
 */
public class NormaBaseZone(
    private val stages: IStageRegistry,
    private val positions: IMatchPlayerPositions,
) {
    public fun siteOf(match: Match, player: PlayerId, radius: Double): BaseSite {
        val own = match.teams.firstOrNull { player in it.members }?.id ?: return BaseSite.NONE
        val loc = positions.positionOf(player) ?: return BaseSite.NONE
        val stage = stages.find(match.stageId) ?: return BaseSite.NONE
        val centres = stage.spawnPoints.filter { it.role == SpawnRole.TEAM && it.team != null }.groupBy { it.team!! }
            .mapValues { (_, pts) -> Vec3(pts.map { it.position.x }.average(), pts.map { it.position.y }.average(), pts.map { it.position.z }.average()) }
        fun inside(c: Vec3) = hypot(loc.position.x - c.x, loc.position.z - c.z) <= radius && abs(loc.position.y - c.y) <= HEIGHT
        if (centres[own]?.let(::inside) == true) return BaseSite.OWN
        return if (centres.any { (team, c) -> team != own && inside(c) }) BaseSite.ENEMY else BaseSite.NONE
    }

    private companion object {
        const val HEIGHT = 12.0
    }
}
