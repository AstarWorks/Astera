package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId

/**
 * Disband a party, removing it and all its members in a single op (Wave 5e).
 *
 * Only the [Party.leader] may invoke this. Non-leaders use
 * [LeavePartyUseCase] instead, which auto-disbands solo parties and demotes
 * leadership in multi-member parties.
 */
public class DisbandPartyUseCase(
    private val repository: IPartyRepository,
) {
    public fun disband(caller: PlayerId, partyId: PartyId): Result<Unit, PartyError> {
        val party = repository.find(partyId)
            ?: return Result.failure(PartyError.NotFound(partyId))
        if (party.leader != caller) {
            return Result.failure(PartyError.NotLeader(caller, partyId))
        }
        repository.remove(partyId)
        return Result.success(Unit)
    }
}
