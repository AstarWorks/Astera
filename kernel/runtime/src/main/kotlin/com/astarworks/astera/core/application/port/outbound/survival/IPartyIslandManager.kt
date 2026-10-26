package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.core.domain.survival.IslandVisibility
import com.astarworks.astera.core.domain.survival.PartyIsland
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.application.usecase.survival.IslandError

/**
 * Outbound port: high-level island lifecycle façade (Wave 5pve-survival /
 * ADR-0107).
 *
 * Composes [IPartyIslandRepository] + [IIslandPersistence] + the domain
 * event bus so use cases / commands / GUIs have one entry point instead
 * of orchestrating three ports each time. The Core default
 * (`InMemoryPartyIslandManager`) is wired in `AsteraCorePlugin.onEnable`.
 *
 * # Why a façade port (not just use cases)
 *
 * Use cases are still the SSOT for business rules; this façade is the
 * thin "compose use cases + ports + publish events" shell that adapters
 * (Paper command, REST admin, GUI button) actually call. Mirrors the
 * `IBossLifecycleService` / `IMatchConfigService` pattern from Waves
 * 5pve / 5e.
 */
public interface IPartyIslandManager {

    /**
     * Provision a fresh island for [partyId] from [templateId]. Fails if
     * the party already owns one or the template is unknown. Publishes
     * [com.astarworks.astera.core.domain.event.PartyIslandCreated] on
     * success.
     */
    public suspend fun create(
        partyId: PartyId,
        createdBy: PlayerId,
        templateId: IslandTemplateId,
        maxMembers: Int,
        visibility: IslandVisibility,
    ): Result<PartyIsland, IslandError>

    /**
     * Bring an `Unloaded` island back online (or accept the already-Active
     * case as a no-op). Resolves the schematic via [IIslandPersistence]
     * and returns the live [WorldId].
     */
    public suspend fun load(islandId: IslandId): Result<WorldId, IslandError>

    /**
     * Release the island world (snapshot + unload). Publishes
     * [com.astarworks.astera.core.domain.event.IslandUnloaded].
     *
     * [reasonKind] is the snake-case audit discriminator
     * (`leader_offline` / `admin_unload` / `archival_freeze`).
     */
    public suspend fun unload(islandId: IslandId, reasonKind: String): Result<Unit, IslandError>

    /**
     * Authorise [visitor] to enter [islandId] — gates on
     * [IslandVisibility] + party membership. Does not actually teleport
     * (that is the Paper adapter's job in Wave 6).
     */
    public suspend fun visit(visitor: PlayerId, islandId: IslandId): Result<Unit, IslandError>
}
