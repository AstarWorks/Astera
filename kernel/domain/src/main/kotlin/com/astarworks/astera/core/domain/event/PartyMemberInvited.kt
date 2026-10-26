package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.PartyInvitationId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5pve-survival / ADR-0107): a party member has
 * just sent an island invitation to another player.
 *
 * # When fired
 *
 * `InvitePartyMemberUseCase` (core plugin) after the invitation is
 * persisted (in-memory in foundation wave) but before the invitee accepts.
 *
 * # Subscribers
 *
 * - Wave 5pve Lobby HUD: surface a click-to-accept toast for the invitee.
 * - Wave 5e audit log: `survival.island.invited` record.
 * - Idea md `idea-notification-gateway`: dispatch to Discord DM if the
 *   invitee is opted in.
 *
 * The corresponding `PartyMemberAccepted` event is intentionally not in
 * Wave 5pve-survival foundation — the acceptance use case publishes the
 * generic `PlayerArrivedOnServer` once the invitee joins the island world.
 */
public data class PartyMemberInvited(
    val invitationId: PartyInvitationId,
    val islandId: IslandId,
    val partyId: PartyId,
    val inviter: PlayerId,
    val invitee: PlayerId,
    val expiresAt: Instant,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(inviter.uuid)

    init {
        require(inviter != invitee) {
            "PartyMemberInvited.inviter ($inviter) must differ from invitee"
        }
        require(expiresAt.isAfter(occurredAt) || expiresAt == occurredAt) {
            "PartyMemberInvited.expiresAt ($expiresAt) must be >= occurredAt ($occurredAt)"
        }
    }
}
