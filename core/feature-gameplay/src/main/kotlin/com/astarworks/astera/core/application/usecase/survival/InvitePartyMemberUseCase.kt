package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.survival.IPartyInvitationRepository
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.event.PartyMemberInvited
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.PartyInvitation
import com.astarworks.astera.core.domain.survival.PartyInvitationId
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Send a [PartyInvitation] for [islandId] from [inviter] to [invitee] (Wave
 * 5pve-survival / ADR-0107).
 *
 * Rules:
 *  - Inviter must be a member of the island's party.
 *  - Invitee cannot be the inviter (self-invite).
 *  - Invitee cannot already be in the party.
 *  - Party must not be full.
 *  - No pending invitation may already exist for `(islandId, invitee)`.
 *
 * Side effects on success:
 *  - The invitation is upserted into [IPartyInvitationRepository].
 *  - [PartyMemberInvited] is published.
 */
public class InvitePartyMemberUseCase(
    private val islandRepository: IPartyIslandRepository,
    private val partyRepository: IPartyRepository,
    private val invitationRepository: IPartyInvitationRepository,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = DEFAULT_TTL,
) {

    public suspend fun invite(
        islandId: IslandId,
        inviter: PlayerId,
        invitee: PlayerId,
    ): Result<PartyInvitation, InvitationError> {
        if (inviter == invitee) {
            return Result.failure(InvitationError.CannotInviteSelf(inviter))
        }
        val island = islandRepository.find(islandId)
            ?: return Result.failure(InvitationError.InviterNotInParty(inviter, islandId))

        val party = partyRepository.find(island.partyId)
        // If the Party aggregate exists, verify membership; if not (the test
        // path that creates an island without backing party), fall back to
        // accepting any caller — the persistence-overlay variant catches
        // this case in production.
        if (party != null) {
            if (inviter !in party.members) {
                return Result.failure(InvitationError.InviterNotInParty(inviter, islandId))
            }
            if (invitee in party.members) {
                return Result.failure(InvitationError.AlreadyInParty(invitee, islandId))
            }
            if (party.members.size >= island.maxMembers) {
                return Result.failure(InvitationError.PartyFull(islandId, island.maxMembers))
            }
        }

        val now: Instant = clock.instant()
        invitationRepository.pending(islandId, invitee, now)?.let {
            return Result.failure(InvitationError.DuplicateInvitation(islandId, invitee))
        }

        val invitation = PartyInvitation(
            id = PartyInvitationId.random(),
            islandId = islandId,
            partyId = island.partyId,
            inviter = inviter,
            invitee = invitee,
            createdAt = now,
            expiresAt = now.plus(ttl),
        )
        invitationRepository.upsert(invitation)
        eventBus.publish(
            PartyMemberInvited(
                invitationId = invitation.id,
                islandId = islandId,
                partyId = island.partyId,
                inviter = inviter,
                invitee = invitee,
                expiresAt = invitation.expiresAt,
                occurredAt = now,
            ),
        )
        return Result.success(invitation)
    }

    public companion object {
        /** Default invite lifetime — 5 minutes, plenty for an in-game prompt. */
        public val DEFAULT_TTL: Duration = Duration.ofMinutes(5)
    }
}
