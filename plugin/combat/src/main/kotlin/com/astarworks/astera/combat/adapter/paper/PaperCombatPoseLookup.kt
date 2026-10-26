package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import org.bukkit.Bukkit

/** Local poses are live; foreign target poses are immutable movement snapshots. */
public class PaperCombatPoseLookup(private val grid: CombatSpatialGrid) : IPlayerPoseLookup {
    override fun poseOf(playerId: PlayerId): PlayerPose? {
        val entity = grid.entity(playerId.uuid) ?: return null
        if (Bukkit.isOwnedByCurrentRegion(entity)) grid.update(entity)
        return grid.snapshot(playerId.uuid)?.pose
    }
}
