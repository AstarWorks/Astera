package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId

/**
 * Create a new [Party] with [leader] as its only initial member (Wave 5e).
 *
 * Fails if the leader is already in another party. The id is generated via
 * [PartyId.random] — callers don't supply it.
 */
public class CreatePartyUseCase(
    private val repository: IPartyRepository,
) {
    public fun create(leader: PlayerId, maxSize: Int): Result<Party, PartyError> {
        repository.forPlayer(leader)?.let {
            return Result.failure(PartyError.AlreadyInParty(leader, it.id))
        }
        val party = Party(
            id = PartyId.random(),
            leader = leader,
            members = setOf(leader),
            maxSize = maxSize,
        )
        repository.upsert(party)
        return Result.success(party)
    }
}
