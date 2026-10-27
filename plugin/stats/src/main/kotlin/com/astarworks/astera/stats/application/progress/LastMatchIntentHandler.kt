package com.astarworks.astera.stats.application.progress

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.ILastMatchStore
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.stats.application.service.LastMatchView
import java.time.Clock

/** Main-menu "last match result" tile (`stats.last`): same output as `/astera stats last`, no typing. */
public class LastMatchIntentHandler(
    private val lastMatches: () -> ILastMatchStore?,
    private val guiAdapter: IGuiAdapter,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val clock: Clock = Clock.systemUTC(),
) {
    public fun handle(@Suppress("UNUSED_PARAMETER") intent: GuiIntent, player: IMcPlayer) {
        guiAdapter.close(player)
        val record = lastMatches()?.find(player.id)
        if (record == null) {
            playerGateway.sendMessage(player.id, messages.render(player.id, MessageKey("astera.stats.last.none")))
            return
        }
        for (line in LastMatchView.lines(record, clock.instant().epochSecond)) {
            playerGateway.sendMessage(player.id, messages.render(player.id, line.key, line.params))
        }
    }

    public companion object {
        public const val INTENT: String = "stats.last"
    }
}
