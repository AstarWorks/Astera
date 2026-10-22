package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.ILastMatchStore
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.stats.application.service.LastMatchView
import java.time.Clock

/**
 * Lobby only. The result screen on the game server is cut short by the
 * transfer back (ADR-0179), so when a player arrives on the lobby and their
 * last match has not been recapped yet, show the card once, a moment after
 * the join messages. It only fires for a match that ended within
 * [MAX_AGE_SECONDS]; older records stay available through `/astera stats last`.
 */
public class ShowLastMatchRecapOnJoinListener(
    private val lastMatches: () -> ILastMatchStore?,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val scheduler: IScheduler,
    private val clock: Clock = Clock.systemUTC(),
) {
    public fun handle(event: PlayerJoinedServer) {
        scheduler.runLater(ticks(RECAP_DELAY_TICKS)) {
            val store = lastMatches() ?: return@runLater
            val record = store.find(event.playerId) ?: return@runLater
            if (store.recapShown(event.playerId)) return@runLater
            val now = clock.instant().epochSecond
            if (now - record.endedAtEpochSec > MAX_AGE_SECONDS) return@runLater
            store.markRecapShown(event.playerId)
            playerGateway.sendMessage(event.playerId, messages.render(event.playerId, RECAP_HEADER))
            for (line in LastMatchView.lines(record, now)) {
                playerGateway.sendMessage(event.playerId, messages.render(event.playerId, line.key, line.params))
            }
        }
    }

    public companion object {
        public const val RECAP_DELAY_TICKS: Long = 60L
        public const val MAX_AGE_SECONDS: Long = 15L * 60L
        public val RECAP_HEADER: com.astarworks.astera.core.domain.i18n.MessageKey =
            com.astarworks.astera.core.domain.i18n.MessageKey("astera.stats.last.recap")
    }
}
