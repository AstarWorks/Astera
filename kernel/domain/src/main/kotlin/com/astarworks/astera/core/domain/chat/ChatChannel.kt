package com.astarworks.astera.core.domain.chat

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId

/**
 * Routing target for a chat message (Wave 5e).
 *
 * Receivers are resolved by the application: [Global] = everyone online;
 * [Team] = members of that team in the current match; [Party] = current
 * party members; [Private] = single recipient.
 */
public sealed interface ChatChannel {
    public data object Global : ChatChannel
    public data class Team(val teamId: TeamId) : ChatChannel
    public data class Party(val partyId: PartyId) : ChatChannel
    public data class Private(val recipient: PlayerId) : ChatChannel
}
