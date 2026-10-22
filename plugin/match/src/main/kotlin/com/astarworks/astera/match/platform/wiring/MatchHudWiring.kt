package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.gamemode.NormaGameMode
import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.service.hud.LobbySidebarService
import com.astarworks.astera.match.application.service.hud.MatchSidebarService
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Sidebar channel wiring (WP14, ADR-0177): the match and lobby sidebar
 * services, their event triggers and their cleanup. Host-neutral (no Bukkit).
 *
 * Triggers are the cheap, targeted ones (match start / phase change / end,
 * kills, pickups, wallet and queue changes); the services' own 1 Hz polls cover
 * everything without an event (clock, pillar HP, captures) and de-duplicate
 * unchanged layouts, so nothing here redraws per tick.
 */
internal object MatchHudWiring {

    fun install(env: MatchWiringEnv, matchmaker: IMatchmaker, hudGateway: IHudGateway) {
        val services = env.ctx.services
        val subscriptions = env.ctx.subscriptions
        val eventBus = env.eventBus
        val modes = services.get<IGameModeFrameworkRegistry>()
        // Resolved per call: the economy plugin registers the ledger after match wiring ran.
        val stars: (com.astarworks.astera.core.domain.player.PlayerId) -> Long? =
            { player -> services.get<ICurrencyLedger>()?.balance(player, Currency.STAR) }

        val modeOf: (MatchId) -> IGameMode? = { id ->
            val key = env.matchModeRegistry.modeKeyOf(id)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { GameModeId(it) }.getOrNull() }
            modes?.let { registry -> (key?.let(registry::get)) ?: registry.get(NormaGameMode.ID) }
        }

        val matchSidebar = MatchSidebarService(
            matches = env.matches,
            modeOf = modeOf,
            hud = hudGateway,
            messages = env.msgRenderer,
            stars = stars,
            scheduler = env.scheduler,
        )
        env.shutdownHooks += { matchSidebar.stop() }

        subscriptions += eventBus.subscribe(MatchStarted::class) { matchSidebar.refresh(it.matchId) }
        subscriptions += eventBus.subscribe(PhaseChanged::class) { matchSidebar.refresh(it.matchId) }
        subscriptions += eventBus.subscribe(EntityPickedUpItem::class) { matchSidebar.refresh(it.matchId) }
        subscriptions += eventBus.subscribe(PlayerKilled::class) { event ->
            env.matches.findMatchOf(event.victim)?.let { matchSidebar.refresh(it.id) }
        }
        subscriptions += eventBus.subscribe(MatchEnded::class) { matchSidebar.release(it.matchId) }
        subscriptions += eventBus.subscribe(CurrencyChanged::class) { matchSidebar.refreshPlayer(it.playerId) }

        if (!env.ctx.serverRole.hostsLobby) return // no lobby on a game-only backend (ADR-0179)
        val lobbySidebar = LobbySidebarService(
            matches = env.matches,
            matchmaker = matchmaker,
            hud = hudGateway,
            messages = env.msgRenderer,
            stars = stars,
            scheduler = env.scheduler,
        )
        env.shutdownHooks += { lobbySidebar.stop() }

        subscriptions += eventBus.subscribe(PlayerJoinedServer::class) { lobbySidebar.onJoined(it.playerId) }
        subscriptions += eventBus.subscribe(PlayerQuitServer::class) { lobbySidebar.onQuit(it.playerId) }
        // F3-6: kit + wallet + queue lines are re-read once the hand-off restore has finished.
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerArrivedOnServer::class) {
            lobbySidebar.refresh(it.playerId)
        }
        subscriptions += eventBus.subscribe(PlayerReturnedToLobby::class) { lobbySidebar.refresh(it.playerId) }
        subscriptions += eventBus.subscribe(MatchQueueJoined::class) { lobbySidebar.refresh(it.playerId) }
        subscriptions += eventBus.subscribe(MatchQueueLeft::class) { lobbySidebar.refresh(it.playerId) }
        subscriptions += eventBus.subscribe(MatchFormed::class) { event -> event.players.forEach(lobbySidebar::refresh) }
        subscriptions += eventBus.subscribe(CurrencyChanged::class) { lobbySidebar.refresh(it.playerId) }

        env.ctx.logger.info("WP14 wired: match + lobby sidebar services (ADR-0177).")
    }
}
