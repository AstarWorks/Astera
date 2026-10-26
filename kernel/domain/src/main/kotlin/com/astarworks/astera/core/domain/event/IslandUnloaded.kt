package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5pve-survival / ADR-0107): a [PartyIsland]
 * world has been released because every party leader is offline (or an
 * admin issued an unload).
 *
 * # When fired
 *
 * `UnloadPartyIslandUseCase` (core plugin) after the schematic snapshot has
 * been persisted via `IIslandPersistence.save` and the runtime world
 * handle has been released by the platform.
 *
 * # Subscribers
 *
 * - Wave 5w backup gateway: trigger an opportunistic restic snapshot of
 *   the unloaded island (`BackupTarget.IslandWorld(islandId)`).
 * - Wave 5pve Lobby HUD: announce "{partyName} の島がアイドル状態に
 *   なりました" to the offline party members on next join.
 * - Wave 12g analytics: per-day unload count for rss accounting.
 *
 * # Why include the unload reason
 *
 * [reasonKind] is a snake-case discriminator so the audit log can group by
 * cause without needing a separate sealed Kotlin hierarchy in Core (mirrors
 * the [BettingMatchResolved.endReasonKind] pattern). Foundation wave kinds:
 *   - `leader_offline` — automatic unload by the offline-detector sweep.
 *   - `admin_unload` — `/astera-survival unload <island-id>`.
 *   - `archival_freeze` — Phase 4 cold-storage transition (reserved).
 */
public data class IslandUnloaded(
    val islandId: IslandId,
    val partyId: PartyId,
    val world: WorldId,
    val reasonKind: String,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(reasonKind.isNotBlank()) {
            "IslandUnloaded.reasonKind must not be blank"
        }
    }
}
