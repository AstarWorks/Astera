package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Leave the party the [player] currently belongs to (Wave 5e).
 *
 * Behaviour:
 * - if the player is the only member: the party is **disbanded** (removed).
 * - if the player is the leader of a multi-member party: leadership is
 *   transferred to the next remaining member, picked deterministically as
 *   the smallest UUID of the remaining set so tests and replays don't drift.
 * - otherwise: the player is simply removed from the member set.
 *
 * Fails if the player is not in any party.
 */
public class LeavePartyUseCase(
    private val repository: IPartyRepository,
) {
    public fun leave(player: PlayerId): Result<Unit, PartyError> {
        val party = repository.forPlayer(player)
            ?: return Result.failure(PartyError.NotInParty(player))

        val remaining = party.members - player
        if (remaining.isEmpty()) {
            // solo party → disband
            repository.remove(party.id)
            return Result.success(Unit)
        }

        val newLeader = if (party.leader == player) {
            // deterministic promotion: smallest UUID wins
            remaining.toSortedSet(compareBy { it.uuid }).first()
        } else {
            party.leader
        }
        repository.upsert(party.copy(leader = newLeader, members = remaining))
        return Result.success(Unit)
    }
}
