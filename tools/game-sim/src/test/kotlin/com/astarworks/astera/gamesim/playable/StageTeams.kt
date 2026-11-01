package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.GameSim

/** Test helper: the teams a shipped stage has team spawns for (the real stage anchors), so tests never hardcode a team count. */
object StageTeams {
    fun of(stage: String): List<String> {
        val sim = GameSim.boot("stage-teams-$stage")
        try {
            val s = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId(stage))) { "unknown stage $stage" }
            return s.spawnPoints.filter { it.role == SpawnRole.TEAM }.mapNotNull { it.team?.value }.distinct()
        } finally {
            sim.shutdown()
        }
    }
}
