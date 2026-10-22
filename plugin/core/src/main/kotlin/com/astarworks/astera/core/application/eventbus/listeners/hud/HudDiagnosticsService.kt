package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * What the HUD channels of one player look like right now, as the gateway
 * holds them (not as the client shows them: a client that dropped a packet is
 * invisible to the server).
 */
public data class HudDiagnostics(
    val playerId: PlayerId,
    val sidebar: String,
    val bossBar: String,
    val actionBar: String,
    val pack: String,
) {
    /** The one log line: `HUD channels for <player>: sidebar=..., bossbar=..., actionbar=..., pack=...`. */
    public fun line(): String =
        "HUD channels for ${playerId.uuid}: sidebar=$sidebar, bossbar=$bossBar, actionbar=$actionBar, pack=$pack"
}

/**
 * Read-only HUD state report (F1-H): makes "the HUD is not drawn" diagnosable
 * from the server log. Logs one line [LOG_DELAY_TICKS] after a join (by then
 * every owner has had its first poll) and one whenever the client reports the
 * resource pack, and answers [describe] for any caller (a future admin
 * command only has to print it). A player with no channel at all is logged at
 * WARN, anything else at INFO.
 */
public class HudDiagnosticsService(
    private val hud: IHudGateway,
    private val scheduler: IScheduler,
    private val packConfigured: Boolean,
    private val logDelayTicks: Long = LOG_DELAY_TICKS,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val packStatus: MutableMap<PlayerId, ResourcePackStatus> = mutableMapOf()

    public fun onJoined(event: PlayerJoinedServer) {
        val playerId = event.playerId
        scheduler.runLater(ticks(logDelayTicks)) {
            if (hud.layoutsFor(playerId).isEmpty()) {
                log.warn("{} (nothing owns any channel)", describe(playerId).line())
            } else {
                log.info(describe(playerId).line())
            }
        }
    }

    public fun onPackStatus(event: ResourcePackStatusChanged) {
        packStatus[event.playerId] = event.status
        log.info("Resource pack status for {}: {}", event.playerId.uuid, event.status)
        log.info(describe(event.playerId).line())
    }

    public fun onQuit(event: PlayerQuitServer) {
        packStatus -= event.playerId
    }

    public fun describe(playerId: PlayerId): HudDiagnostics {
        val layouts = hud.layoutsFor(playerId)
        val sidebar = layouts.filterIsInstance<HudLayout.Sidebar>().firstOrNull()
        val bossBars = layouts.filterIsInstance<HudLayout.BossBarSlot>()
        val actionBar = layouts.filterIsInstance<HudLayout.ActionBar>().firstOrNull()
        val status = layouts.filterIsInstance<HudLayout.StatusLine>().firstOrNull()
        return HudDiagnostics(
            playerId = playerId,
            sidebar = sidebar?.let { "${it.lines.size} lines (${it.titleKey.value})" } ?: "none",
            bossBar = if (bossBars.isEmpty()) "none" else bossBars.joinToString("+") { "slot${it.slot}" },
            actionBar = buildString {
                append(actionBar?.let { "readout(${nameOf(it.component)})" } ?: "no readout")
                append(" + ")
                append(
                    when (status?.component) {
                        null -> "no status line"
                        is HudComponent.FontText -> "status line (glyph bar)"
                        else -> "status line (text)"
                    },
                )
            },
            pack = packStatus[playerId]?.name ?: if (packConfigured) "pushed, no client report" else "not configured",
        )
    }

    private fun nameOf(component: HudComponent): String = when (component) {
        is HudComponent.Text -> component.messageKey.value
        else -> component::class.simpleName ?: "component"
    }

    public companion object {
        public const val LOG_DELAY_TICKS: Long = 60L
    }
}
