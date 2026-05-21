package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.adapter.api.hud.BossBarColor
import com.astarworks.astera.core.adapter.api.hud.BossBarId
import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.event.lifecycle.ServerShutdownStarted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.ILobbyHudCoordinator
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueEntry

/**
 * Wave 5w / ADR-0089 γ — lobby waiting-room HUD (Hypixel-style).
 *
 * Composes Wave 5b vendor-neutral HUD primitives ([IBossBarManager],
 * [IScoreboardManager]) plus per-player action-bar pushes via [IMcServer]
 * to render queue position, ETA countdown, mode label, and a cancel hint.
 *
 * The service is **vendor-neutral by Konsist rule** ("LobbyWaitingRoomHud
 * must use vendor-neutral hud ports only" — see ArchitectureTest.kt). It
 * does not import Bukkit family directly; Paper renderings flow through
 * the priority-100 overlay for [IBossBarManager] / [IScoreboardManager]
 * that Wave 6a will land.
 *
 * **Polling model**: callers tick [refresh] from a 20-tick scheduler (1s);
 * Phase 2 may replace with `IScoreboardManager.subscribe(playerId, binding)`
 * reactive plumbing. The polling rate is configurable via
 * `content/hud/lobby-waiting.json` (parsed by the wiring layer; this
 * service receives the resolved [tickPeriodTicks]).
 */
public class LobbyWaitingRoomHud(
    private val matchmaker: IMatchmaker,
    private val bossBars: IBossBarManager,
    private val scoreboards: IScoreboardManager,
    private val mcServer: IMcServer,
    private val eventBus: IDomainEventBus,
    @Suppress("unused") private val scheduler: IScheduler,
    /** How often [refresh] is expected to be invoked, ticks. Phase 1 PoC default: 20 (=1s). */
    @Suppress("unused") private val tickPeriodTicks: Long = 20L,
) : ILobbyHudCoordinator {

    private val tracked: MutableSet<PlayerId> = mutableSetOf()
    private val subscriptions: MutableList<IDomainEventBus.Subscription> = mutableListOf()

    /**
     * Wire this coordinator to the event bus. Idempotent: a subsequent call
     * after [unsubscribe] re-registers cleanly. Phase C wires this from
     * `AsteraMatchPlugin.onEnable`.
     */
    public fun subscribe() {
        subscriptions += eventBus.subscribe(MatchQueueJoined::class) { refresh(it.playerId) }
        subscriptions += eventBus.subscribe(MatchQueueLeft::class) { e ->
            if (e.reasonTag != "match-formed") onLeave(e.playerId)
            // For "match-formed", the MatchFormed handler below clears the HUD
            // after pushing the "Match found! Teleporting…" action bar.
        }
        subscriptions += eventBus.subscribe(MatchFormed::class) { e ->
            for (p in e.players) {
                pushActionBar(p, MessageKey("astera.lobby.match-found"))
                onLeave(p)
            }
        }
        subscriptions += eventBus.subscribe(ServerShutdownStarted::class) {
            for (p in tracked.toList()) onLeave(p)
        }
    }

    public fun unsubscribe() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
    }

    override fun onEnter(playerId: PlayerId) {
        if (tracked.add(playerId)) {
            ensureBossBar(playerId)
            ensureScoreboard(playerId)
            refresh(playerId)
        }
    }

    override fun onLeave(playerId: PlayerId) {
        if (tracked.remove(playerId)) {
            bossBars.bossBar(barIdFor(playerId))?.let { bossBars.hide(playerId, it) }
            scoreboards.remove(scoreboardIdFor(playerId))
        }
    }

    override fun refresh(playerId: PlayerId) {
        if (playerId !in tracked) return
        val entry = matchmaker.queueStatus(playerId)
        val text = renderActionBar(entry)
        pushActionBar(playerId, text.key, text.placeholders)
        updateScoreboardLines(playerId, entry)
        updateBossBarProgress(playerId, entry)
    }

    override fun trackedPlayers(): Set<PlayerId> = tracked.toSet()

    // ─── internals ─────────────────────────────────────────────────────────

    private data class HudText(val key: MessageKey, val placeholders: Map<String, String>)

    private fun renderActionBar(entry: QueueEntry?): HudText {
        if (entry == null) {
            return HudText(MessageKey("astera.lobby.not-queued"), emptyMap())
        }
        return HudText(
            key = MessageKey("astera.lobby.queue-status"),
            placeholders = mapOf(
                "mode" to modeLabel(entry.mode),
                "position" to positionInQueue(entry).toString(),
                "eta" to entry.estimatedWaitSec.toString(),
            ),
        )
    }

    private fun positionInQueue(entry: QueueEntry): Int {
        val pending = matchmaker.pendingByMode(entry.mode).toList()
        val idx = pending.indexOfFirst { it.playerId == entry.playerId }
        return if (idx >= 0) idx + 1 else 0
    }

    private fun modeLabel(mode: MatchmakingMode): String = when (mode) {
        is MatchmakingMode.Norma -> "Norma"
        is MatchmakingMode.Custom -> "Custom:${mode.adminConfigured.value}"
        is MatchmakingMode.Tournament -> "Tournament:${mode.tournamentMatchId}"
    }

    private fun ensureBossBar(playerId: PlayerId) {
        val id = barIdFor(playerId)
        val existing = bossBars.bossBar(id)
        if (existing == null) {
            val bar = bossBars.createBossBar(
                id = id,
                title = MessageKey("astera.lobby.bossbar.title"),
                progress = 0.0,
                color = BossBarColor.YELLOW,
            )
            bossBars.show(playerId, bar)
        } else {
            bossBars.show(playerId, existing)
        }
    }

    private fun ensureScoreboard(playerId: PlayerId) {
        val id = scoreboardIdFor(playerId)
        val board = scoreboards.scoreboard(id) ?: scoreboards.createScoreboard(
            id = id,
            title = MessageKey("astera.lobby.scoreboard.title"),
        )
        scoreboards.show(playerId, board)
    }

    private fun updateBossBarProgress(playerId: PlayerId, entry: QueueEntry?) {
        val bar = bossBars.bossBar(barIdFor(playerId)) ?: return
        bar.progress = if (entry == null) {
            0.0
        } else {
            val pending = matchmaker.pendingByMode(entry.mode).size.coerceAtLeast(1)
            val pos = positionInQueue(entry).coerceAtLeast(1)
            (1.0 - (pos.toDouble() / pending.toDouble())).coerceIn(0.0, 1.0)
        }
    }

    private fun updateScoreboardLines(playerId: PlayerId, entry: QueueEntry?) {
        val sb = scoreboards.scoreboard(scoreboardIdFor(playerId)) ?: return
        // Wipe any prior lines (14 = top, 0 = bottom).
        for (i in 0..14) sb.removeLine(i)
        if (entry == null) {
            sb.addLine(0, MessageKey("astera.lobby.scoreboard.not-queued"))
            return
        }
        sb.addLine(3, MessageKey("astera.lobby.scoreboard.mode"), mapOf("mode" to modeLabel(entry.mode)))
        sb.addLine(2, MessageKey("astera.lobby.scoreboard.position"), mapOf("position" to positionInQueue(entry).toString()))
        sb.addLine(1, MessageKey("astera.lobby.scoreboard.eta"), mapOf("eta" to entry.estimatedWaitSec.toString()))
        sb.addLine(0, MessageKey("astera.lobby.scoreboard.cancel-hint"))
    }

    private fun pushActionBar(playerId: PlayerId, key: MessageKey, placeholders: Map<String, String> = emptyMap()) {
        mcServer.findPlayer(playerId)?.sendActionBar(key, placeholders)
    }

    private fun barIdFor(playerId: PlayerId): BossBarId =
        BossBarId("lobby-wait-${playerId.uuid}")

    private fun scoreboardIdFor(playerId: PlayerId): ScoreboardId =
        ScoreboardId("lobby-wait-${playerId.uuid}")
}
