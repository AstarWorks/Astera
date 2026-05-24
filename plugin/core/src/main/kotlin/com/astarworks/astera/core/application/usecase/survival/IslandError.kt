package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.IslandTemplateId

/**
 * Expected failure modes of [CreatePartyIslandUseCase] / [LoadPartyIslandUseCase] /
 * [UnloadPartyIslandUseCase] / [VisitPartyIslandUseCase] (Wave 5pve-survival /
 * ADR-0107).
 *
 * Sealed so use-case callers must exhaustively `when` on the failure side.
 * Every variant carries an i18n [messageKey] so the Lobby HUD / chat layer
 * can render a localized error without case-matching on the type.
 */
public sealed class IslandError {
    public abstract val messageKey: MessageKey

    /** Caller's party already owns an island (one party = one island in foundation). */
    public data class PartyAlreadyOwnsIsland(val partyId: PartyId, val existing: IslandId) : IslandError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.error.party-already-owns")
    }

    /** Template id resolves to no `IIslandTemplateRegistry` entry. */
    public data class UnknownTemplate(val templateId: IslandTemplateId) : IslandError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.error.unknown-template")
    }

    /** No island with [islandId] exists. */
    public data class NotFound(val islandId: IslandId) : IslandError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.error.not-found")
    }

    /** The party's leader is offline; load / visit is refused. */
    public data class LeaderOffline(val islandId: IslandId, val partyId: PartyId) : IslandError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.error.leader-offline")
    }

    /** Player is not in the owning party + not on the visibility allow-list. */
    public data class NotPermittedToVisit(val islandId: IslandId, val playerId: PlayerId) : IslandError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.error.not-permitted-to-visit")
    }

    /** Island is in a state that disallows the requested transition. */
    public data class WrongStatus(val islandId: IslandId, val expected: Set<IslandStatus>, val actual: IslandStatus) : IslandError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.error.wrong-status")
    }

    /** Underlying world / schematic adapter failed (I/O / placement error). */
    public data class PlatformFailure(val islandId: IslandId, val detail: String) : IslandError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.error.platform-failure")
    }
}
