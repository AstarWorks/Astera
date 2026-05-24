package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import java.time.Instant
import java.util.UUID

/**
 * Pending invitation for [invitee] to join [islandId]'s party (Wave
 * 5pve-survival / ADR-0107).
 *
 * Distinct from [com.astarworks.astera.core.domain.social.Party] joins:
 *
 *  - Joining a `Party` is an immediate "I am part of the squad" toggle
 *    (Wave 5e: `JoinPartyUseCase` accepts the [PartyId] directly).
 *  - An island invite is a "you may load this island's world" handle. The
 *    invitee may already be in the party but not yet allowed onto the
 *    island (admin-blacklist case), or may be a friend invited via
 *    [IslandVisibility.FriendsAllowed] without joining the party at all.
 *
 * Invitations are runtime-only in Wave 5pve-survival foundation: they live
 * in `InMemoryPartyInvitationRepository`, expire on `expiresAt`, and
 * disappear on server restart. Persistence promotion to Postgres is
 * Phase 4 (paired with a cross-server invite delivery channel).
 */
public data class PartyInvitation(
    val id: PartyInvitationId,
    val islandId: IslandId,
    val partyId: PartyId,
    val inviter: PlayerId,
    val invitee: PlayerId,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(inviter != invitee) {
            "PartyInvitation.inviter ($inviter) must differ from invitee"
        }
        require(expiresAt.isAfter(createdAt)) {
            "PartyInvitation.expiresAt ($expiresAt) must be after createdAt ($createdAt)"
        }
    }

    /** True if [now] is past [expiresAt]. */
    public fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)
}

/** Server-minted identifier for a [PartyInvitation]. */
@JvmInline
public value class PartyInvitationId(public val value: UUID) {
    override fun toString(): String = value.toString()

    public companion object {
        public fun random(): PartyInvitationId = PartyInvitationId(UUID.randomUUID())
    }
}
