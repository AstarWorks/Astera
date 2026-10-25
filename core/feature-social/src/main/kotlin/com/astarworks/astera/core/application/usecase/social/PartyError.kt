package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId

/**
 * Expected failure modes of the party use cases (Wave 5e).
 *
 * Shared by [CreatePartyUseCase], [JoinPartyUseCase], [LeavePartyUseCase],
 * and [DisbandPartyUseCase].
 */
public sealed class PartyError {
    public abstract val messageKey: MessageKey

    /** Caller is already in a party (cannot create or join another). */
    public data class AlreadyInParty(val player: PlayerId, val partyId: PartyId) : PartyError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.party.error.already-in-party")
    }

    /** Caller is not a member of any party (cannot leave / cannot disband). */
    public data class NotInParty(val player: PlayerId) : PartyError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.party.error.not-in-party")
    }

    /** Party is at capacity; cannot accept another member. */
    public data class PartyFull(val partyId: PartyId, val maxSize: Int) : PartyError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.party.error.party-full")
    }

    /** Caller is not the party leader (required for disband). */
    public data class NotLeader(val player: PlayerId, val partyId: PartyId) : PartyError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.party.error.not-leader")
    }

    /** No party exists with the given id. */
    public data class NotFound(val partyId: PartyId) : PartyError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.party.error.not-found")
    }
}
