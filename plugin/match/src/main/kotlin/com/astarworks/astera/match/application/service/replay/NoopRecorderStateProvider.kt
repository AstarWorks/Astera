package com.astarworks.astera.match.application.service.replay

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.replay.Vec3Snapshot
import com.astarworks.astera.match.application.port.outbound.IRecorderStateProvider

/**
 * Default [IRecorderStateProvider] for Phase 1 PoC (Wave 5v, ADR-0094).
 *
 * Returns no participants and null for every state query, so key frames
 * generated against it carry empty maps. The `ReplayCaptureEngine` still
 * runs its scheduler tick and emits well-formed [com.astarworks.astera
 * .core.domain.replay.KeyFrame]s — they simply contain no per-player rows
 * until a richer provider lands.
 *
 * Wave 6a `PaperRecorderStateProvider` overlays this at priority 100 with
 * a `Bukkit.getOnlinePlayers()` + `player.location` / `player.health` /
 * `player.inventory.itemInMainHand` impl. Wave 5n's
 * `IPlayerLocationQueryService` (when merged) can be wrapped by a third
 * provider for in-memory cache reads.
 */
public class NoopRecorderStateProvider : IRecorderStateProvider {
    override fun participantsOf(matchId: MatchId): Collection<PlayerId> = emptyList()
    override fun positionOf(playerId: PlayerId): Vec3Snapshot? = null
    override fun healthOf(playerId: PlayerId): Double? = null
    override fun activeItemOf(playerId: PlayerId): ItemId? = null
}
