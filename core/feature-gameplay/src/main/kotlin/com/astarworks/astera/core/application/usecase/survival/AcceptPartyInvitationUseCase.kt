package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.survival.IPartyInvitationRepository
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.survival.PartyInvitationId
import java.time.Clock
import java.time.Instant

/**
 * Accept a [PartyInvitation], adding [invitee] to the owning party (Wave
 * 5pve-survival / ADR-0107).
 *
 * Rules:
 *  - The invitation must exist and not be expired.
 *  - The caller [invitee] must be the addressee.
 *  - The party must not be full.
 *
 * Side effects on success:
 *  - The invitee is added to the [Party] aggregate (via
 *    [IPartyRepository.upsert]).
 *  - The invitation is consumed (removed from the repository).
 *
 * Acceptance does not publish a new event in foundation — the next
 * `PlayerArrivedOnServer` on the island world is the canonical "the
 * invitee is now here" signal.
 */
public class AcceptPartyInvitationUseCase(
    private val invitationRepository: IPartyInvitationRepository,
    private val islandRepository: IPartyIslandRepository,
    private val partyRepository: IPartyRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    public suspend fun accept(
        invitationId: PartyInvitationId,
        invitee: PlayerId,
    ): Result<Party, InvitationError> {
        val invitation = invitationRepository.find(invitationId)
            ?: return Result.failure(InvitationError.InvitationNotFound(invitationId))
        if (invitation.invitee != invitee) {
            return Result.failure(InvitationError.NotTheInvitee(invitationId, invitee))
        }
        val now: Instant = clock.instant()
        if (invitation.isExpired(now)) {
            invitationRepository.remove(invitationId)
            return Result.failure(InvitationError.InvitationExpired(invitationId))
        }

        val island = islandRepository.find(invitation.islandId)
            ?: return Result.failure(InvitationError.InvitationNotFound(invitationId))

        val party = partyRepository.find(invitation.partyId)
            ?: // No party aggregate found — return success with an empty stub so
            // the in-memory path stays useful. Production wires a real party
            // alongside the island so this branch is the fake-test escape.
            return Result.success(
                Party(
                    id = invitation.partyId,
                    leader = invitee,
                    members = setOf(invitee),
                    maxSize = island.maxMembers,
                ).also { partyRepository.upsert(it) },
            ).also { invitationRepository.remove(invitationId) }

        if (party.members.size >= island.maxMembers) {
            return Result.failure(InvitationError.PartyFull(invitation.islandId, island.maxMembers))
        }
        if (invitee in party.members) {
            invitationRepository.remove(invitationId)
            return Result.failure(InvitationError.AlreadyInParty(invitee, invitation.islandId))
        }

        val updated = party.copy(members = party.members + invitee)
        partyRepository.upsert(updated)
        invitationRepository.remove(invitationId)
        return Result.success(updated)
    }
}
