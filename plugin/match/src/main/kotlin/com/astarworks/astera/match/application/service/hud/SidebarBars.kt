package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/** Shared sidebar decoration: coloured progress bars and the server-address footer (lobby and match). */
internal object SidebarBars {

    private const val DEFAULT_CELLS: Int = 10

    fun percent(value: Long, max: Long): Int =
        if (max <= 0L) 0 else (value.coerceIn(0L, max) * 100 / max).toInt()

    /** `■■■□□□` as MiniMessage: [color] for the filled cells, dark gray for the rest. */
    fun bar(value: Long, max: Long, color: String, cells: Int = DEFAULT_CELLS): String {
        val filled = (percent(value, max) * cells / 100).coerceIn(0, cells)
        val on = if (filled > 0) "<$color>${"■".repeat(filled)}</$color>" else ""
        val off = if (filled < cells) "<dark_gray>${"□".repeat(cells - filled)}</dark_gray>" else ""
        return on + off
    }

    /** The last sidebar row: the server address, set in one place (`astera.hud.server-address` in the language files). */
    fun footer(viewer: PlayerId, messages: IMessageRenderer): HudComponent.Text =
        HudComponent.Text(
            MessageKey("astera.hud.sidebar.footer"),
            placeholders = mapOf("address" to messages.render(viewer, MessageKey("astera.hud.server-address"))),
        )
}
