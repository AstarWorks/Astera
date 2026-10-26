package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5pve-survival / ADR-0107): a Skyblock party
 * island has just been provisioned.
 *
 * # When fired
 *
 * `CreatePartyIslandUseCase` (core plugin) after a successful template
 * stamp + repository upsert. The world handle is already live at the
 * point of publish.
 *
 * # Subscribers
 *
 * - Wave 5e audit log: persist `survival.island.created` audit record.
 * - Wave 5pve Lobby HUD: announce "{leader} の島が利用可能になりました" to
 *   the leader's party.
 * - Wave 12g analytics: per-day island creation count.
 *
 * # Why polymorphic-untyped party identity
 *
 * The Core event marker stays vendor-neutral. [partyId] is the canonical
 * [PartyId] handle (already a Core type, Wave 5e); [createdBy] is the
 * leader at creation time (immutable on the event — the live [PartyIsland]
 * record carries the mutable leader handle).
 */
public data class PartyIslandCreated(
    val islandId: IslandId,
    val partyId: PartyId,
    val world: WorldId,
    val createdBy: PlayerId,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(createdBy.uuid)

    init {
        require(islandId.value.isNotBlank()) { "PartyIslandCreated.islandId must not be blank" }
    }
}
