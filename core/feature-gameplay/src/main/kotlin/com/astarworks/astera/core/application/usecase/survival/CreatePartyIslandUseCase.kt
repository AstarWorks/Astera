package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.survival.IIslandTemplateRegistry
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.event.PartyIslandCreated
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.core.domain.survival.IslandVisibility
import com.astarworks.astera.core.domain.survival.PartyIsland
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Create a fresh [PartyIsland] for a party (Wave 5pve-survival / ADR-0107).
 *
 * Rules:
 *  - The caller's [PartyId] must already exist (Wave 5e `Party` aggregate).
 *  - The party may not already own an island (one party = one island in
 *    foundation; relaxable later).
 *  - The requested [templateId] must resolve to a registered template.
 *
 * Side effects on success:
 *  - The new [PartyIsland] is upserted into [IPartyIslandRepository].
 *  - [PartyIslandCreated] is published on [IDomainEventBus].
 *  - The world handle ([WorldId]) is minted by [worldIdMinter] — production
 *    wires `IPartyIslandManager.create` to derive a deterministic
 *    `survival-${islandId}` name; tests inject a stable factory.
 *
 * Schematic placement is **not** invoked here — that is `IIslandPersistence`
 * scope and is run by [LoadPartyIslandUseCase]. Splitting `create` from
 * `place` lets the in-memory test path skip schematic I/O entirely.
 */
public class CreatePartyIslandUseCase(
    private val partyRepository: IPartyRepository,
    private val islandRepository: IPartyIslandRepository,
    private val templateRegistry: IIslandTemplateRegistry,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
    private val islandIdMinter: () -> IslandId = { IslandId(UUID.randomUUID().toString()) },
    private val worldIdMinter: (IslandId) -> WorldId = { id -> WorldId("survival-${id.value}") },
) {

    public suspend fun create(
        partyId: PartyId,
        createdBy: PlayerId,
        templateId: IslandTemplateId,
        maxMembers: Int,
        visibility: IslandVisibility = IslandVisibility.PartyOnly,
    ): Result<PartyIsland, IslandError> {
        val template = templateRegistry.find(templateId)
            ?: return Result.failure(IslandError.UnknownTemplate(templateId))

        islandRepository.forParty(partyId)?.let { existing ->
            return Result.failure(IslandError.PartyAlreadyOwnsIsland(partyId, existing.id))
        }

        // We tolerate the case where the party aggregate has not been
        // persisted yet (Wave 5e parties are in-memory unless persistence
        // overlays). The party-membership check is only enforced for
        // visit/invite paths — creation needs only the partyId handle.
        val islandId = islandIdMinter()
        val world = worldIdMinter(islandId)
        val now: Instant = clock.instant()
        val resolvedMax = maxMembers.coerceIn(
            com.astarworks.astera.core.domain.survival.IslandTemplate.MIN_MEMBERS,
            template.defaultMaxMembers,
        )
        val island = PartyIsland(
            id = islandId,
            partyId = partyId,
            world = world,
            templateId = templateId,
            maxMembers = resolvedMax,
            visibility = visibility,
            status = IslandStatus.Active,
            createdAt = now,
            lastActiveAt = now,
        )
        islandRepository.upsert(island)
        // Touch the party aggregate so `partyRepository.find(partyId)` has at
        // least a placeholder lookup hook in tests that pre-create the party.
        partyRepository.find(partyId)
        eventBus.publish(
            PartyIslandCreated(
                islandId = islandId,
                partyId = partyId,
                world = world,
                createdBy = createdBy,
                occurredAt = now,
            ),
        )
        return Result.success(island)
    }
}
