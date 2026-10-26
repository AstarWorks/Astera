package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.norma.MissionProgress
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaTarget

/** One mission as the player sees it now: [current] already counts only the running daily / weekly window. */
public data class NormaView(
    public val norma: Norma,
    public val current: Long,
    public val required: Long,
    public val completed: Boolean,
)

public interface INormaService {
    public fun activeNormasFor(playerId: PlayerId): Collection<Norma>
    public suspend fun progress(playerId: PlayerId): Collection<MissionProgress>

    /** Every defined mission with the player's progress in the current period (daily / weekly rows roll over). */
    public suspend fun views(playerId: PlayerId): List<NormaView>

    /**
     * Records [delta] progress for all normas matching the type of [target].
     * Returns the list of [MissionProgress] entries that were newly completed
     * by this call.
     */
    public suspend fun recordProgress(playerId: PlayerId, target: NormaTarget, delta: Long): List<MissionProgress>

    /**
     * Marks a specific norma as completed for [playerId]. Returns the updated
     * [MissionProgress] if found, null if the norma is unknown for this player.
     */
    public suspend fun markComplete(playerId: PlayerId, normaId: NormaId): MissionProgress?
}
