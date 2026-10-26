package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Move leadership from [currentLeader] to [newLeader] (Wave G
 * 5player-prefs-social, ADR-0124).
 *
 * Preconditions:
 * - [currentLeader] is in a party ([PartyError.NotInParty])
 * - [currentLeader] actually owns the leader slot ([PartyError.NotLeader])
 * - [newLeader] is already a member of that party ([PartyError.NotInParty]
 *   for the target — they have to join first via [JoinPartyUseCase])
 * - [newLeader] != [currentLeader] — the no-op case is rejected so the GUI /
 *   chat command can render a clear "already the leader" message rather than
 *   silently no-op.
 *
 * Members + maxSize stay; only the [com.astarworks.astera.core.domain.social.Party.leader]
 * field flips. After this returns successfully [currentLeader] is now an
 * ordinary member.
 */
public class TransferPartyLeaderUseCase(
    private val repository: IPartyRepository,
) {
    public fun transfer(
        currentLeader: PlayerId,
        newLeader: PlayerId,
    ): Result<Unit, PartyError> {
        val party = repository.forPlayer(currentLeader)
            ?: return Result.failure(PartyError.NotInParty(currentLeader))
        if (party.leader != currentLeader) {
            return Result.failure(PartyError.NotLeader(currentLeader, party.id))
        }
        if (newLeader == currentLeader) {
            // No-op transfer: surface as NotLeader for the GUI to render
            // "you are already the leader". The hierarchy doesn't yet have a
            // dedicated "same-person transfer" variant; NotLeader is the
            // closest match (the candidate is, in effect, already leader).
            return Result.failure(PartyError.NotLeader(newLeader, party.id))
        }
        if (newLeader !in party.members) {
            return Result.failure(PartyError.NotInParty(newLeader))
        }
        repository.upsert(party.copy(leader = newLeader))
        return Result.success(Unit)
    }
}
