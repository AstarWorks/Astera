package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Eject [target] from the caller's party (Wave G 5player-prefs-social,
 * ADR-0124).
 *
 * Preconditions:
 * - the caller is in a party ([PartyError.NotInParty])
 * - the caller is that party's leader ([PartyError.NotLeader])
 * - [target] is a member of that party ([PartyError.NotInParty] for the target)
 * - [target] != [leader] — the leader cannot kick themselves; they must
 *   transfer leadership first (Wave G [TransferPartyLeaderUseCase]) or use
 *   [LeavePartyUseCase] which deterministically promotes the smallest UUID.
 *
 * Solo parties cannot exist here — by definition the caller is the only
 * member then, and the self-kick branch above already rejected the call.
 * After a successful kick the party persists with `members - target`;
 * leadership stays with the caller.
 */
public class KickPartyMemberUseCase(
    private val repository: IPartyRepository,
) {
    public suspend fun kick(leader: PlayerId, target: PlayerId): Result<Unit, PartyError> {
        val party = repository.forPlayer(leader)
            ?: return Result.failure(PartyError.NotInParty(leader))
        if (party.leader != leader) {
            return Result.failure(PartyError.NotLeader(leader, party.id))
        }
        if (target == leader) {
            // Leader cannot self-kick; route through LeavePartyUseCase /
            // TransferPartyLeaderUseCase instead.
            return Result.failure(PartyError.NotLeader(leader, party.id))
        }
        if (target !in party.members) {
            return Result.failure(PartyError.NotInParty(target))
        }
        repository.upsert(party.copy(members = party.members - target))
        return Result.success(Unit)
    }
}
