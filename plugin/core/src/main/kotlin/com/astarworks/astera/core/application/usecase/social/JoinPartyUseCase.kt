package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId

/**
 * Join an existing [Party] (Wave 5e).
 *
 * Fails if:
 * - the party doesn't exist ([PartyError.NotFound])
 * - the player is already in a party ([PartyError.AlreadyInParty]) — including
 *   the target party itself (idempotent re-join is treated as a user error
 *   so the calling UI can surface a clear "you're already in this party"
 *   message rather than silently no-op)
 * - the party is at capacity ([PartyError.PartyFull])
 */
public class JoinPartyUseCase(
    private val repository: IPartyRepository,
) {
    public fun join(player: PlayerId, partyId: PartyId): Result<Party, PartyError> {
        val party = repository.find(partyId)
            ?: return Result.failure(PartyError.NotFound(partyId))
        repository.forPlayer(player)?.let {
            return Result.failure(PartyError.AlreadyInParty(player, it.id))
        }
        if (party.members.size >= party.maxSize) {
            return Result.failure(PartyError.PartyFull(partyId, party.maxSize))
        }
        val updated = party.copy(members = party.members + player)
        repository.upsert(updated)
        return Result.success(updated)
    }
}
