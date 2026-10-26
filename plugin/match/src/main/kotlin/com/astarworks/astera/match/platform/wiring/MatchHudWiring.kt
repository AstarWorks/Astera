package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.gamemode.SkirmishGameMode
import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.service.hud.LobbySidebarService
import com.astarworks.astera.match.application.service.hud.MatchNameplateService
import com.astarworks.astera.match.application.service.hud.TabHeaderFooterService
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
        // In-match stars (match-scoped, never the wallet): the line shows only in matches whose stage has generators.
        val matchStars = checkNotNull(env.stars) { "MatchStarWiring runs before the HUD wiring" }
        val norma: (com.astarworks.astera.core.domain.player.PlayerId) -> com.astarworks.astera.match.domain.norma.NormaHud? = { player ->
            env.matches.findMatchOf(player)
                ?.takeIf { matchStars.stageHasStars(it.stageId.value) }
                ?.let { matchStars.norma.hudOf(player) }
        }

        val modeOf: (MatchId) -> IGameMode? = { id ->
            val key = env.matchModeRegistry.modeKeyOf(id)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { GameModeId(it) }.getOrNull() }
            modes?.let { registry -> (key?.let(registry::get)) ?: registry.get(SkirmishGameMode.ID) }
        }

        val matchSidebar = MatchSidebarService(
            matches = env.matches,
            modeOf = modeOf,
            hud = hudGateway,
            messages = env.msgRenderer,
            norma = norma,
            scheduler = env.scheduler,
        )
        env.shutdownHooks += { matchSidebar.stop() }

        subscriptions += eventBus.subscribe(MatchStarted::class, EventDelivery.Global) { matchSidebar.refresh(it.matchId) }
        subscriptions += eventBus.subscribe(PhaseChanged::class, EventDelivery.Global) { matchSidebar.refresh(it.matchId) }
        subscriptions += eventBus.subscribe(EntityPickedUpItem::class, EventDelivery.Global) { matchSidebar.refresh(it.matchId) }
        subscriptions += eventBus.subscribe(PlayerKilled::class, EventDelivery.Global) { event ->
            env.matches.findMatchOf(event.victim)?.let { matchSidebar.refresh(it.id) }
        }
        subscriptions += eventBus.subscribe(MatchEnded::class, EventDelivery.Global) { matchSidebar.release(it.matchId) }
        // A rejoining participant has a blank client: drop the dedupe entry and redraw the match sidebar at once.
        subscriptions += eventBus.subscribe(PlayerQuitServer::class, EventDelivery.Global) { matchSidebar.forget(it.playerId) }
        subscriptions += eventBus.subscribe(com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch::class, EventDelivery.Global) {
            matchSidebar.forget(it.playerId)
            matchSidebar.refreshPlayer(it.playerId)
        }
        subscriptions += eventBus.subscribe(com.astarworks.astera.match.domain.event.StarsCollected::class, EventDelivery.Global) { matchSidebar.refreshPlayer(it.playerId) }
        subscriptions += eventBus.subscribe(com.astarworks.astera.match.domain.event.NormaLevelUp::class, EventDelivery.Global) { matchSidebar.refreshPlayer(it.playerId) }

        installNameplatesAndTab(env, modeOf)

        if (!env.ctx.serverRole.hostsLobby) return // no lobby on a game-only backend (ADR-0179)
        val lobbySidebar = LobbySidebarService(
            matches = env.matches,
            matchmaker = matchmaker,
            hud = hudGateway,
            messages = env.msgRenderer,
            scheduler = env.scheduler,
            gold = { player -> services.get<ICurrencyLedger>()?.balance(player, Currency.GOLD) },
            settings = { env.queueSettings.current() },
            progress = { env.queueProgress },
        )
        env.shutdownHooks += { lobbySidebar.stop() }

        subscriptions += eventBus.subscribe(PlayerJoinedServer::class, EventDelivery.Global) { lobbySidebar.onJoined(it.playerId) }
        subscriptions += eventBus.subscribe(PlayerQuitServer::class, EventDelivery.Global) { lobbySidebar.onQuit(it.playerId) }
        // F3-6: kit + wallet + queue lines are re-read once the hand-off restore has finished.
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerArrivedOnServer::class, EventDelivery.Global) {
            lobbySidebar.refresh(it.playerId)
        }
        subscriptions += eventBus.subscribe(PlayerReturnedToLobby::class, EventDelivery.Global) { lobbySidebar.refresh(it.playerId) }
        // A forfeiter (left the match for good) is a lobby player again.
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerLeftMatch::class, EventDelivery.Global) { lobbySidebar.refresh(it.playerId) }
        subscriptions += eventBus.subscribe(MatchQueueJoined::class, EventDelivery.Global) { lobbySidebar.refresh(it.playerId) }
        subscriptions += eventBus.subscribe(MatchQueueLeft::class, EventDelivery.Global) { lobbySidebar.refresh(it.playerId) }
        subscriptions += eventBus.subscribe(MatchFormed::class, EventDelivery.Global) { event -> event.players.forEach(lobbySidebar::refresh) }
        subscriptions += eventBus.subscribe(CurrencyChanged::class, EventDelivery.Global) { lobbySidebar.refresh(it.playerId) }

        env.ctx.logger.info("WP14 wired: match + lobby sidebar services (ADR-0177).")
    }

    /**
     * ADR-0206: team-coloured nameplates + HP under the name (match participants), and the
     * tab-list header / footer (everyone). Both run on game and lobby backends alike.
     */
    private fun installNameplatesAndTab(env: MatchWiringEnv, modeOf: (MatchId) -> IGameMode?) {
        val services = env.ctx.services
        val subscriptions = env.ctx.subscriptions
        val eventBus = env.eventBus

        services.get<IPlayerLabelGateway>()?.let { labels ->
            val health = services.get<IPlayerHealthGateway>()
            val nameplates = MatchNameplateService(
                matches = env.matches,
                labels = labels,
                messages = env.msgRenderer,
                // A player whose container holds no HP yet is at full health (same rule as the status line).
                hpOf = { player ->
                    health?.let { it.getCurrentHealth(player) ?: it.getMaxHealth(player) }
                        ?: com.astarworks.astera.core.application.eventbus.listeners.hud.HpStatusHudService.DEFAULT_MAX_HP
                },
                scheduler = env.scheduler,
            )
            env.shutdownHooks += { nameplates.stop() }
            subscriptions += eventBus.subscribe(MatchStarted::class, EventDelivery.Global) { nameplates.refresh(it.matchId) }
            subscriptions += eventBus.subscribe(PhaseChanged::class, EventDelivery.Global) { nameplates.refresh(it.matchId) }
            subscriptions += eventBus.subscribe(PlayerHealthChanged::class, EventDelivery.Global) { nameplates.refreshMatchOf(it.playerId) }
            subscriptions += eventBus.subscribe(MatchEnded::class, EventDelivery.Global) { nameplates.release(it.matchId) }
            subscriptions += eventBus.subscribe(PlayerReturnedToLobby::class, EventDelivery.Global) { nameplates.onLeft(it.playerId) }
            subscriptions += eventBus.subscribe(PlayerQuitServer::class, EventDelivery.Global) { nameplates.onLeft(it.playerId) }
        }

        services.get<ITabList>()?.let { tabList ->
            val tab = TabHeaderFooterService(
                matches = env.matches,
                tabList = tabList,
                messages = env.msgRenderer,
                modeOf = modeOf,
                scheduler = env.scheduler,
            )
            env.shutdownHooks += { tab.stop() }
            subscriptions += eventBus.subscribe(PlayerJoinedServer::class, EventDelivery.Global) { tab.onJoined(it.playerId) }
            subscriptions += eventBus.subscribe(PlayerQuitServer::class, EventDelivery.Global) { tab.onQuit(it.playerId) }
            subscriptions += eventBus.subscribe(MatchStarted::class, EventDelivery.Global) { tab.refreshAll() }
            subscriptions += eventBus.subscribe(MatchEnded::class, EventDelivery.Global) { tab.refreshAll() }
            subscriptions += eventBus.subscribe(PlayerReturnedToLobby::class, EventDelivery.Global) { tab.refreshAll() }
        }
    }
}
