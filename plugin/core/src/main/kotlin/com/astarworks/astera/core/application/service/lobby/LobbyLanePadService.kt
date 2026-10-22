package com.astarworks.astera.core.application.service.lobby

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneCatalog
import com.astarworks.astera.core.domain.event.LobbyLanePadEntered
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.lobby.LobbyLanePadLayout
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects players stepping onto a lobby queue lane pad and publishes [LobbyLanePadEntered]
 * (F3-1). Entry only: standing on a pad does not re-fire and stepping off does nothing
 * (leaving the queue is explicit). Pad positions come from [LobbyLanePadLayout] for the
 * lanes in the catalog, re-derived whenever that list changes.
 */
public class LobbyLanePadService(
    private val lobbyWorld: WorldId,
    private val centerX: Int,
    private val centerZ: Int,
    private val surfaceY: Int,
    private val catalog: () -> ILobbyLaneCatalog?,
    private val eventBus: IDomainEventBus,
    private val onWalk: (PlayerId, WorldId, Vec3) -> Unit = { _, _, _ -> },
) {
    private val standingOn = ConcurrentHashMap<PlayerId, String>()

    @Volatile private var cachedLanes: List<String> = emptyList()
    @Volatile private var cachedPads: Map<String, Vec3> = emptyMap()

    /** Current pad centres by lane id. */
    public fun pads(): Map<String, Vec3> {
        val lanes = catalog()?.laneIds().orEmpty()
        if (lanes != cachedLanes) {
            cachedPads = LobbyLanePadLayout.padCenters(lanes, centerX, centerZ, surfaceY)
            cachedLanes = lanes
        }
        return cachedPads
    }

    /** Reports the player's feet position; returns the lane id when this move entered a pad. */
    public fun onMoved(playerId: PlayerId, world: WorldId, at: Vec3): String? {
        if (world != lobbyWorld) {
            standingOn.remove(playerId)
            return null
        }
        onWalk(playerId, world, at)
        val lane = LobbyLanePadLayout.laneAt(pads(), surfaceY, at)
        if (lane == null) {
            standingOn.remove(playerId)
            return null
        }
        if (standingOn.put(playerId, lane) == lane) return null
        eventBus.publish(LobbyLanePadEntered(playerId, lane))
        return lane
    }

    public fun forget(playerId: PlayerId) {
        standingOn.remove(playerId)
    }
}
