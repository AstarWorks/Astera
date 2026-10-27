package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IObjectiveStage
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.application.port.outbound.ObjectiveLook
import java.util.concurrent.ConcurrentHashMap

/** [IObjectiveStage] for the simulator: records who carries what, which markers lie where and which bars show. */
public class SimObjectiveStage : IObjectiveStage {
    public data class Marker(val world: WorldId, val position: Vec3, val look: ObjectiveLook)
    public data class Bar(val title: String, val progress: Double, val color: ObjectiveBarColor)

    private val carrying: MutableMap<PlayerId, ObjectiveLook> = ConcurrentHashMap()
    private val markers: MutableMap<Pair<MatchId, String>, Marker> = ConcurrentHashMap()
    private val bars: MutableMap<Pair<PlayerId, String>, Bar> = ConcurrentHashMap()

    /** What [player] carries right now, `null` = nothing. */
    public fun carrying(player: PlayerId): ObjectiveLook? = carrying[player]

    /** World markers of [match] by key. */
    public fun markers(match: MatchId): Map<String, Marker> =
        markers.filterKeys { it.first == match }.mapKeys { it.key.second }

    /** Boss bar [key] of [player], `null` = hidden. */
    public fun bar(player: PlayerId, key: String): Bar? = bars[player to key]

    override fun setCarrying(player: PlayerId, look: ObjectiveLook?) {
        if (look == null) carrying.remove(player) else carrying[player] = look
    }

    override fun showMarker(match: MatchId, key: String, world: WorldId, at: Vec3, look: ObjectiveLook) {
        markers[match to key] = Marker(world, at, look)
    }

    override fun removeMarker(match: MatchId, key: String) {
        markers.remove(match to key)
    }

    override fun showBar(player: PlayerId, key: String, title: String, progress: Double, color: ObjectiveBarColor) {
        bars[player to key] = Bar(title, progress, color)
    }

    override fun hideBar(player: PlayerId, key: String) {
        bars.remove(player to key)
    }

    override fun clearMatch(match: MatchId, players: Collection<PlayerId>) {
        markers.keys.removeAll { it.first == match }
        for (p in players) {
            carrying.remove(p)
            bars.keys.removeAll { it.first == p }
        }
    }
}
