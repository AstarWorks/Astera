package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IWorldLabels
import java.util.concurrent.ConcurrentHashMap

/** [IWorldLabels] for the simulator: records which labels stand where and what each viewer group reads. */
public class SimWorldLabels : IWorldLabels {
    public data class Label(val world: WorldId, val at: Vec3, val variants: Map<String, Set<PlayerId>>)

    private val labels: MutableMap<Pair<MatchId, String>, Label> = ConcurrentHashMap()

    /** Every live label of [match] by key. */
    public fun labels(match: MatchId): Map<String, Label> = labels.filterKeys { it.first == match }.mapKeys { it.key.second }

    /** What [viewer] reads on label [key] of [match] (`null` = not shown to them). */
    public fun textFor(match: MatchId, key: String, viewer: PlayerId): String? =
        labels[match to key]?.variants?.entries?.firstOrNull { viewer in it.value }?.key

    override fun show(match: MatchId, key: String, world: WorldId, at: Vec3, variants: Map<String, Set<PlayerId>>) {
        labels[match to key] = Label(world, at, variants.mapValues { it.value.toSet() })
    }

    override fun remove(match: MatchId, key: String) {
        labels.remove(match to key)
    }

    override fun clearMatch(match: MatchId) {
        labels.keys.removeIf { it.first == match }
    }
}
