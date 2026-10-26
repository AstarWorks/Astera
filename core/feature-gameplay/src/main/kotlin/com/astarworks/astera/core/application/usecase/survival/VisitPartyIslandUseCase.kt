package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.IslandVisibility

/**
 * Authorise [visitor] to enter [islandId] (Wave 5pve-survival / ADR-0107).
 *
 * Decision matrix:
 *  - Island missing → [IslandError.NotFound].
 *  - Island not in [IslandStatus.HOT] → [IslandError.WrongStatus] (caller
 *    must `LoadPartyIslandUseCase` first).
 *  - [IslandVisibility.PartyOnly]: visitor must be a party member.
 *  - [IslandVisibility.FriendsAllowed]: party member OR on the friend list.
 *  - [IslandVisibility.Public]: anyone allowed.
 *
 * Authorisation only; the Paper adapter handles the actual teleport in
 * Wave 6.
 */
public class VisitPartyIslandUseCase(
    private val islandRepository: IPartyIslandRepository,
    private val partyRepository: IPartyRepository,
) {

    public suspend fun visit(visitor: PlayerId, islandId: IslandId): Result<Unit, IslandError> {
        val island = islandRepository.find(islandId)
            ?: return Result.failure(IslandError.NotFound(islandId))

        if (island.status !in IslandStatus.HOT) {
            return Result.failure(
                IslandError.WrongStatus(islandId, IslandStatus.HOT, island.status),
            )
        }

        val party = partyRepository.find(island.partyId)
        val isMember = party?.members?.contains(visitor) == true
        val permitted = when (val v = island.visibility) {
            IslandVisibility.PartyOnly -> isMember
            is IslandVisibility.FriendsAllowed -> isMember || visitor in v.invitedFriends
            IslandVisibility.Public -> true
        }
        return if (permitted) {
            Result.success(Unit)
        } else {
            Result.failure(IslandError.NotPermittedToVisit(islandId, visitor))
        }
    }
}
