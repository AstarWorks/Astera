package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandManager
import com.astarworks.astera.core.application.usecase.survival.CreatePartyIslandUseCase
import com.astarworks.astera.core.application.usecase.survival.IslandError
import com.astarworks.astera.core.application.usecase.survival.LoadPartyIslandUseCase
import com.astarworks.astera.core.application.usecase.survival.UnloadPartyIslandUseCase
import com.astarworks.astera.core.application.usecase.survival.VisitPartyIslandUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.core.domain.survival.IslandVisibility
import com.astarworks.astera.core.domain.survival.PartyIsland
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default [IPartyIslandManager] (Wave 5pve-survival / ADR-0107).
 *
 * Thin composition over the four lifecycle use cases — the use cases own
 * the rules; this façade just routes calls. Keeping the façade trivial
 * makes the Wave 6 Paper-adapter swap a single-class change (replace the
 * facade with a Paper-aware variant that also runs world ticket
 * acquisition).
 */
public class DefaultPartyIslandManager(
    private val createUseCase: CreatePartyIslandUseCase,
    private val loadUseCase: LoadPartyIslandUseCase,
    private val unloadUseCase: UnloadPartyIslandUseCase,
    private val visitUseCase: VisitPartyIslandUseCase,
) : IPartyIslandManager {

    override suspend fun create(
        partyId: PartyId,
        createdBy: PlayerId,
        templateId: IslandTemplateId,
        maxMembers: Int,
        visibility: IslandVisibility,
    ): Result<PartyIsland, IslandError> =
        createUseCase.create(partyId, createdBy, templateId, maxMembers, visibility)

    override suspend fun load(islandId: IslandId): Result<WorldId, IslandError> =
        loadUseCase.load(islandId)

    override suspend fun unload(islandId: IslandId, reasonKind: String): Result<Unit, IslandError> =
        unloadUseCase.unload(islandId, reasonKind)

    override suspend fun visit(visitor: PlayerId, islandId: IslandId): Result<Unit, IslandError> =
        visitUseCase.visit(visitor, islandId)
}
