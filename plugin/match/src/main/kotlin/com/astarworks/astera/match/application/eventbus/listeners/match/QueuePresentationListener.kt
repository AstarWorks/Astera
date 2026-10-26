package com.astarworks.astera.match.application.eventbus.listeners.match

import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.presentation.PresentationMoments

/**
 * The queue's two audible moments: a confirmation when a player (or party member) is queued, and the "match found"
 * sting with a title for everyone in the formed roster. Both go through [IPresentation], so what they sound like is
 * `content/presentation/events.yaml`'s business.
 */
public class QueuePresentationListener(private val presentation: IPresentation) {

    public fun onJoined(event: MatchQueueJoined) {
        presentation.play(PresentationMoments.QUEUE_JOIN, event.playerId)
    }

    public fun onFormed(event: MatchFormed) {
        for (player in event.players) presentation.play(PresentationMoments.MATCH_FOUND, player)
    }
}
