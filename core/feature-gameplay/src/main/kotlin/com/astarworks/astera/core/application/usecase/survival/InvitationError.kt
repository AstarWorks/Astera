package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.PartyInvitationId

/**
 * Expected failure modes of [InvitePartyMemberUseCase] /
 * [AcceptPartyInvitationUseCase] (Wave 5pve-survival / ADR-0107).
 */
public sealed class InvitationError {
    public abstract val messageKey: MessageKey

    /** Inviter is not in the owning party (only members can invite). */
    public data class InviterNotInParty(val inviter: PlayerId, val islandId: IslandId) : InvitationError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.invite.error.inviter-not-in-party")
    }

    /** Invitee is already in the party (no need to invite). */
    public data class AlreadyInParty(val invitee: PlayerId, val islandId: IslandId) : InvitationError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.invite.error.already-in-party")
    }

    /** Party is at [maxMembers] capacity. */
    public data class PartyFull(val islandId: IslandId, val maxMembers: Int) : InvitationError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.invite.error.party-full")
    }

    /** Invitation with [invitationId] not found (already accepted / expired / never existed). */
    public data class InvitationNotFound(val invitationId: PartyInvitationId) : InvitationError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.invite.error.not-found")
    }

    /** Caller [playerId] is not the addressee of [invitationId]. */
    public data class NotTheInvitee(val invitationId: PartyInvitationId, val playerId: PlayerId) : InvitationError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.invite.error.not-the-invitee")
    }

    /** Invitation has expired past its `expiresAt`. */
    public data class InvitationExpired(val invitationId: PartyInvitationId) : InvitationError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.invite.error.expired")
    }

    /** Pending invitation already exists for `(islandId, invitee)`. */
    public data class DuplicateInvitation(val islandId: IslandId, val invitee: PlayerId) : InvitationError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.invite.error.duplicate")
    }

    /** Inviter is trying to invite themselves. */
    public data class CannotInviteSelf(val playerId: PlayerId) : InvitationError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.invite.error.cannot-invite-self")
    }
}
