package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.star.StarSource

/**
 * Hands budget stars straight to a player (mining, block breaks, caches): asks the [StarBudget], credits the
 * match-scoped store, plays the pickup feedback and publishes [StarSupplied] + [StarsCollected] (so Norma sees it).
 */
public class StarIssuer(
    private val budget: StarBudget,
    private val store: IMatchStarStore,
    private val presentation: IPresentation,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {
    /** Credits up to [requested] stars of [source] to [player]; returns how many the budget allowed (0 = cap hit, nothing happened). */
    public fun issue(matchId: MatchId, player: PlayerId, source: StarSource, requested: Int): Int {
        val granted = budget.grant(matchId, source, player, requested)
        if (granted <= 0) return 0
        credit(matchId, player, source, granted)
        return granted
    }

    /** Credits stars that were already granted (or moved from another player). */
    public fun credit(matchId: MatchId, player: PlayerId, source: StarSource?, amount: Int) {
        val total = store.add(matchId, player, amount)
        presentation.play(PresentationMoments.STAR_PICKUP, player, mapOf("amount" to amount.toString(), "total" to total.toString()))
        if (source != null) eventBus.publish(StarSupplied(matchId, GeneratorId(source.generatorId), amount, null, scheduler.currentTick()))
        eventBus.publish(StarsCollected(matchId, player, amount, total))
    }
}
