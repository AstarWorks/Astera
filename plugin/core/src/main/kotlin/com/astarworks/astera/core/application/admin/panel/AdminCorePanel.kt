package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi.pager
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.admin.AdminOnlinePlayer
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.application.port.outbound.admin.IServerHealthCheck
import com.astarworks.astera.core.application.port.outbound.maintenance.IMaintenanceModeService
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.usecase.admin.ReloadAllContentUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.maintenance.MaintenanceMode
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * Core-owned pages of the `/astera-admin` panel (F1-D, ADR-0183): the hub, the
 * Players page (online list + per-player actions), the Server page and the
 * Self-tools page.
 *
 * Core knows nothing about weapons, matches, currency or combat. Those plugins
 * contribute their own hub sections and per-player / self / server tiles
 * through [IAdminPanelRegistry] and handle the resulting `admin.<area>.`
 * intents themselves; this class only lays the tiles out and injects the
 * `target` payload.
 */
public class AdminCorePanel(
    private val guard: AdminPanelGuard,
    private val registry: IAdminPanelRegistry,
    private val guiAdapter: IGuiAdapter,
    private val players: IAdminPlayerGateway,
    private val maintenance: IMaintenanceModeService,
    private val health: IServerHealthCheck,
    private val reloadAll: ReloadAllContentUseCase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Register Core's tiles + the `admin.hub.` / `admin.players.` / `admin.server.` / `admin.self.` handlers. */
    public fun install(router: IGuiIntentRouter): List<ServiceRegistry.Registration> {
        val regs = mutableListOf<ServiceRegistry.Registration>()
        regs += registry.register(hubTile("core.players", 30, ICON_PLAYERS, K_HUB_PLAYERS, K_HUB_PLAYERS_LORE, OPEN_PLAYERS))
        regs += registry.register(hubTile("core.server", 40, ICON_SERVER, K_HUB_SERVER, K_HUB_SERVER_LORE, OPEN_SERVER))
        regs += registry.register(hubTile("core.self", 50, ICON_SELF, K_HUB_SELF, K_HUB_SELF_LORE, OPEN_SELF))
        regs += registry.register(
            AdminPanelTile(
                id = "core.self.fly",
                point = AdminPanelPoint.SELF,
                order = 10,
                icon = ICON_FLY,
                name = GuiText(K_SELF_FLY),
                lore = listOf(GuiText(K_SELF_FLY_LORE)),
                intent = GuiIntent(SELF_FLY),
            ),
        )
        regs += router.register(AdminIntents.HUB_OPEN, guard.guarded { _, p -> openHub(p) })
        regs += router.register(AdminIntents.CORE_PLAYERS_PREFIX, guard.guarded(::onPlayersIntent))
        regs += router.register(AdminIntents.CORE_SERVER_PREFIX, guard.guarded(::onServerIntent))
        regs += router.register(AdminIntents.CORE_SELF_PREFIX, guard.guarded(::onSelfIntent))
        return regs
    }

    // ---- Hub ------------------------------------------------------------

    /** Open the hub for [player] (also what `/astera-admin` calls after its own permission check). */
    public fun openHub(player: IMcPlayer): Boolean = AdminPanelUi.show(guiAdapter, player, buildHub())

    public fun buildHub(): GuiSpec {
        val tiles = registry.tiles(AdminPanelPoint.HUB)
        return GuiSpec.build(HUB_ID, K_TITLE_HUB, GuiSize.CHEST_27) {
            tiles.take(HUB_SLOTS.size).forEachIndexed { i, tile ->
                slot(SlotIndex(HUB_SLOTS[i]), AdminPanelUi.tileSlot(tile))
            }
            slot(SlotIndex(26), AdminPanelUi.close())
        }
    }

    // ---- Players --------------------------------------------------------

    private fun onPlayersIntent(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            OPEN_PLAYERS -> show(player, buildPlayerList(intent.payload["page"]?.toIntOrNull() ?: 0))
            PLAYERS_PICK -> targetOf(intent)?.let { showPlayerActions(player, it) }
                ?: guard.tell(player, K_PLAYER_GONE)
            PLAYERS_TP -> withOnlineTarget(player, intent) { target ->
                val ok = players.teleportToPlayer(player.id, target.id)
                guard.tell(player, if (ok) K_TP_DONE else K_PLAYER_GONE, mapOf("player" to target.name))
                if (ok) guard.record(player, PLAYERS_TP, target.id.toString())
            }
            PLAYERS_BRING -> withOnlineTarget(player, intent) { target ->
                val ok = players.teleportToPlayer(target.id, player.id)
                guard.tell(player, if (ok) K_BRING_DONE else K_PLAYER_GONE, mapOf("player" to target.name))
                if (ok) guard.record(player, PLAYERS_BRING, target.id.toString())
            }
            PLAYERS_GAMEMODE -> withOnlineTarget(player, intent) { target ->
                val mode = players.toggleGameMode(target.id)
                if (mode == null) {
                    guard.tell(player, K_PLAYER_GONE, mapOf("player" to target.name))
                } else {
                    guard.tell(player, K_GAMEMODE_DONE, mapOf("player" to target.name, "mode" to mode.name.lowercase()))
                    guard.record(player, PLAYERS_GAMEMODE, target.id.toString(), "mode=${mode.name.lowercase()}")
                }
            }
            PLAYERS_KICK -> withOnlineTarget(player, intent) { target ->
                if (target.id == player.id) {
                    guard.tell(player, K_KICK_SELF)
                } else {
                    val ok = players.kick(target.id, KICK_REASON)
                    guard.tell(player, if (ok) K_KICK_DONE else K_PLAYER_GONE, mapOf("player" to target.name))
                    if (ok) guard.record(player, PLAYERS_KICK, target.id.toString())
                    show(player, buildPlayerList(0))
                }
            }
            else -> log.warn("Unknown admin.players intent '{}' from {}", intent.key, player.name)
        }
    }

    /** Online-player grid (heads). 45 per page. */
    public fun buildPlayerList(requestedPage: Int): GuiSpec {
        val online = players.onlinePlayers()
        val pages = AdminPanelUi.pageCount(online.size, PLAYERS_PER_PAGE)
        val page = AdminPanelUi.clampPage(requestedPage, pages)
        return GuiSpec.build(PLAYERS_ID, K_TITLE_PLAYERS, GuiSize.CHEST_54) {
            online.drop(page * PLAYERS_PER_PAGE).take(PLAYERS_PER_PAGE).forEachIndexed { i, who ->
                slot(
                    SlotIndex(i),
                    GuiSlot.Static(
                        itemId = ICON_HEAD,
                        onLeftClick = GuiIntent(PLAYERS_PICK, mapOf(AdminPanelUi.PAYLOAD_TARGET to who.id.toString())),
                        displayName = GuiText(K_PLAYER_ROW, mapOf("player" to who.name)),
                        lore = listOf(GuiText(K_PLAYER_ROW_LORE)),
                        headOf = who.id,
                    ),
                )
            }
            slot(SlotIndex(45), AdminPanelUi.back())
            pager(OPEN_PLAYERS, emptyMap(), page, pages, prevSlot = 47, pageSlot = 49, nextSlot = 51)
            slot(SlotIndex(53), AdminPanelUi.close())
        }
    }

    private fun showPlayerActions(viewer: IMcPlayer, target: AdminOnlinePlayer) {
        show(viewer, buildPlayerActions(target))
    }

    /** Per-player action page: Core's own actions + every registered [AdminPanelPoint.PLAYER] tile. */
    public fun buildPlayerActions(target: AdminOnlinePlayer): GuiSpec {
        val payload = mapOf(AdminPanelUi.PAYLOAD_TARGET to target.id.toString())
        return GuiSpec.build(PLAYER_ACTIONS_ID, K_TITLE_PLAYER_ACTIONS, GuiSize.CHEST_36) {
            slot(
                SlotIndex(4),
                GuiSlot.Static(
                    itemId = ICON_HEAD,
                    displayName = GuiText(K_PLAYER_ROW, mapOf("player" to target.name)),
                    headOf = target.id,
                ),
            )
            val own = listOf(
                GuiSlot.Static(ICON_TP, GuiIntent(PLAYERS_TP, payload), displayName = GuiText(K_ACT_TP), lore = listOf(GuiText(K_ACT_TP_LORE))),
                GuiSlot.Static(ICON_BRING, GuiIntent(PLAYERS_BRING, payload), displayName = GuiText(K_ACT_BRING), lore = listOf(GuiText(K_ACT_BRING_LORE))),
                GuiSlot.Static(ICON_GAMEMODE, GuiIntent(PLAYERS_GAMEMODE, payload), displayName = GuiText(K_ACT_GAMEMODE), lore = listOf(GuiText(K_ACT_GAMEMODE_LORE))),
                // Kick needs a deliberate shift-click: no plain-click handler on purpose.
                GuiSlot.Static(ICON_KICK, onShiftClick = GuiIntent(PLAYERS_KICK, payload), displayName = GuiText(K_ACT_KICK), lore = listOf(GuiText(K_ACT_KICK_LORE))),
            )
            val contributed = registry.tiles(AdminPanelPoint.PLAYER).map { AdminPanelUi.tileSlot(it, payload) }
            (own + contributed).take(ACTION_SLOTS.size).forEachIndexed { i, s -> slot(SlotIndex(ACTION_SLOTS[i]), s) }
            slot(
                SlotIndex(27),
                GuiSlot.Static(
                    itemId = AdminPanelUi.BACK_ICON,
                    onLeftClick = GuiIntent(OPEN_PLAYERS),
                    displayName = GuiText(K_BACK_TO_PLAYERS),
                ),
            )
            slot(SlotIndex(28), AdminPanelUi.back())
            slot(SlotIndex(35), AdminPanelUi.close())
        }
    }

    // ---- Server ---------------------------------------------------------

    private fun onServerIntent(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            OPEN_SERVER -> show(player, buildServerPage())
            SERVER_RELOAD -> {
                when (val r = reloadAll.execute()) {
                    is Result.Success -> guard.tell(player, K_RELOAD_OK, mapOf("count" to r.value.reloaded.size.toString()))
                    is Result.Failure -> guard.tell(
                        player,
                        K_RELOAD_PARTIAL,
                        mapOf(
                            "ok" to r.error.summary.reloaded.size.toString(),
                            "failed" to r.error.summary.failed.joinToString(", "),
                        ),
                    )
                }
                guard.record(player, SERVER_RELOAD)
            }
            SERVER_MAINTENANCE -> {
                val now = clock.instant()
                val nowOn = when (maintenance.current()) {
                    is MaintenanceMode.On -> { maintenance.setOff(now); false }
                    is MaintenanceMode.Off -> { maintenance.setOn(now, MAINTENANCE_REASON, null); true }
                }
                guard.tell(player, if (nowOn) K_MAINTENANCE_ON else K_MAINTENANCE_OFF)
                guard.record(player, SERVER_MAINTENANCE, detail = if (nowOn) "on" else "off")
                show(player, buildServerPage())
            }
            SERVER_HEALTH -> {
                val report = health.snapshot()
                guard.tell(player, K_HEALTH_HEADER, mapOf("state" to report.overall.name.lowercase()))
                report.individuals.toSortedMap().forEach { (name, state) ->
                    guard.tell(player, K_HEALTH_LINE, mapOf("name" to name, "state" to state.name.lowercase()))
                }
            }
            else -> log.warn("Unknown admin.server intent '{}' from {}", intent.key, player.name)
        }
    }

    public fun buildServerPage(): GuiSpec {
        val maintenanceOn = maintenance.current() is MaintenanceMode.On
        val overall = health.snapshot().overall.name.lowercase()
        return GuiSpec.build(SERVER_ID, K_TITLE_SERVER, GuiSize.CHEST_27) {
            slot(SlotIndex(10), GuiSlot.Static(ICON_RELOAD, GuiIntent(SERVER_RELOAD), displayName = GuiText(K_SRV_RELOAD), lore = listOf(GuiText(K_SRV_RELOAD_LORE))))
            slot(
                SlotIndex(11),
                GuiSlot.Static(
                    itemId = if (maintenanceOn) ICON_ON else ICON_OFF,
                    onLeftClick = GuiIntent(SERVER_MAINTENANCE),
                    displayName = GuiText(K_SRV_MAINTENANCE, mapOf("state" to if (maintenanceOn) "ON" else "OFF")),
                    lore = listOf(GuiText(K_SRV_MAINTENANCE_LORE)),
                ),
            )
            slot(
                SlotIndex(12),
                GuiSlot.Static(
                    itemId = ICON_HEALTH,
                    onLeftClick = GuiIntent(SERVER_HEALTH),
                    displayName = GuiText(K_SRV_HEALTH, mapOf("state" to overall)),
                    lore = listOf(GuiText(K_SRV_HEALTH_LORE)),
                ),
            )
            registry.tiles(AdminPanelPoint.SERVER).take(SERVER_EXTRA_SLOTS.size).forEachIndexed { i, tile ->
                slot(SlotIndex(SERVER_EXTRA_SLOTS[i]), AdminPanelUi.tileSlot(tile))
            }
            slot(SlotIndex(18), AdminPanelUi.back())
            slot(SlotIndex(26), AdminPanelUi.close())
        }
    }

    // ---- Self -----------------------------------------------------------

    private fun onSelfIntent(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            OPEN_SELF -> show(player, buildSelfPage(player.id))
            SELF_FLY -> {
                val now = players.toggleFlight(player.id)
                if (now != null) {
                    guard.tell(player, if (now) K_FLY_ON else K_FLY_OFF)
                    guard.record(player, SELF_FLY, player.id.toString(), if (now) "on" else "off")
                }
            }
            else -> log.warn("Unknown admin.self intent '{}' from {}", intent.key, player.name)
        }
    }

    /** Self-tools page: every registered [AdminPanelPoint.SELF] tile with `target=viewer`. */
    public fun buildSelfPage(viewer: PlayerId): GuiSpec {
        val payload = mapOf(AdminPanelUi.PAYLOAD_TARGET to viewer.toString())
        return GuiSpec.build(SELF_ID, K_TITLE_SELF, GuiSize.CHEST_27) {
            registry.tiles(AdminPanelPoint.SELF).take(SELF_SLOTS.size).forEachIndexed { i, tile ->
                slot(SlotIndex(SELF_SLOTS[i]), AdminPanelUi.tileSlot(tile, payload))
            }
            slot(SlotIndex(18), AdminPanelUi.back())
            slot(SlotIndex(26), AdminPanelUi.close())
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun show(player: IMcPlayer, spec: GuiSpec) {
        if (!AdminPanelUi.show(guiAdapter, player, spec)) guard.tell(player, K_OPEN_REJECTED)
    }

    private fun targetOf(intent: GuiIntent): AdminOnlinePlayer? {
        val raw = intent.payload[AdminPanelUi.PAYLOAD_TARGET] ?: return null
        val id = runCatching { PlayerId(UUID.fromString(raw)) }.getOrNull() ?: return null
        return players.onlinePlayers().firstOrNull { it.id == id }
    }

    private fun withOnlineTarget(player: IMcPlayer, intent: GuiIntent, block: (AdminOnlinePlayer) -> Unit) {
        val target = targetOf(intent)
        if (target == null) guard.tell(player, K_PLAYER_GONE) else block(target)
    }

    private fun hubTile(
        id: String,
        order: Int,
        icon: ItemId,
        name: MessageKey,
        lore: MessageKey,
        intentKey: String,
    ): AdminPanelTile = AdminPanelTile(
        id = id,
        point = AdminPanelPoint.HUB,
        order = order,
        icon = icon,
        name = GuiText(name),
        lore = listOf(GuiText(lore)),
        intent = GuiIntent(intentKey),
    )

    public companion object {
        public const val OPEN_PLAYERS: String = "admin.players.open"
        public const val PLAYERS_PICK: String = "admin.players.pick"
        public const val PLAYERS_TP: String = "admin.players.tp"
        public const val PLAYERS_BRING: String = "admin.players.bring"
        public const val PLAYERS_GAMEMODE: String = "admin.players.gamemode"
        public const val PLAYERS_KICK: String = "admin.players.kick"
        public const val OPEN_SERVER: String = "admin.server.open"
        public const val SERVER_RELOAD: String = "admin.server.reload"
        public const val SERVER_MAINTENANCE: String = "admin.server.maintenance"
        public const val SERVER_HEALTH: String = "admin.server.health"
        public const val OPEN_SELF: String = "admin.self.open"
        public const val SELF_FLY: String = "admin.self.fly"

        public const val PLAYERS_PER_PAGE: Int = 45
        private const val KICK_REASON: String = "Kicked by an administrator."
        private const val MAINTENANCE_REASON: String = "admin-panel"

        private val HUB_SLOTS: List<Int> = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
        private val ACTION_SLOTS: List<Int> = (9..26).toList()
        private val SERVER_EXTRA_SLOTS: List<Int> = listOf(13, 14, 15, 16)
        private val SELF_SLOTS: List<Int> = listOf(10, 11, 12, 13, 14, 15, 16)

        public val HUB_ID: GuiId = GuiId("admin-hub")
        public val PLAYERS_ID: GuiId = GuiId("admin-players")
        public val PLAYER_ACTIONS_ID: GuiId = GuiId("admin-player-actions")
        public val SERVER_ID: GuiId = GuiId("admin-server")
        public val SELF_ID: GuiId = GuiId("admin-self")

        private val ICON_PLAYERS = ItemId("gui-admin-players")
        private val ICON_SERVER = ItemId("gui-admin-server")
        private val ICON_SELF = ItemId("gui-admin-self")
        private val ICON_HEAD = ItemId("gui-admin-player-head")
        private val ICON_FLY = ItemId("gui-admin-fly")
        private val ICON_TP = ItemId("gui-admin-teleport")
        private val ICON_BRING = ItemId("gui-admin-bring")
        private val ICON_GAMEMODE = ItemId("gui-admin-gamemode")
        private val ICON_KICK = ItemId("gui-admin-kick")
        private val ICON_RELOAD = ItemId("gui-admin-reload")
        private val ICON_HEALTH = ItemId("gui-admin-health")
        private val ICON_ON = ItemId("indicator-on")
        private val ICON_OFF = ItemId("indicator-off")

        private fun k(s: String) = MessageKey("astera.admin.panel.$s")

        public val K_TITLE_HUB: MessageKey = k("hub.title")
        private val K_TITLE_PLAYERS = k("players.title")
        private val K_TITLE_PLAYER_ACTIONS = k("players.actions.title")
        private val K_TITLE_SERVER = k("server.title")
        private val K_TITLE_SELF = k("self.title")

        private val K_HUB_PLAYERS = k("hub.players")
        private val K_HUB_PLAYERS_LORE = k("hub.players.lore")
        private val K_HUB_SERVER = k("hub.server")
        private val K_HUB_SERVER_LORE = k("hub.server.lore")
        private val K_HUB_SELF = k("hub.self")
        private val K_HUB_SELF_LORE = k("hub.self.lore")

        private val K_PLAYER_ROW = k("players.row")
        private val K_PLAYER_ROW_LORE = k("players.row.lore")
        private val K_PLAYER_GONE = k("players.gone")
        private val K_BACK_TO_PLAYERS = k("players.back")
        private val K_ACT_TP = k("players.act.tp")
        private val K_ACT_TP_LORE = k("players.act.tp.lore")
        private val K_ACT_BRING = k("players.act.bring")
        private val K_ACT_BRING_LORE = k("players.act.bring.lore")
        private val K_ACT_GAMEMODE = k("players.act.gamemode")
        private val K_ACT_GAMEMODE_LORE = k("players.act.gamemode.lore")
        private val K_ACT_KICK = k("players.act.kick")
        private val K_ACT_KICK_LORE = k("players.act.kick.lore")
        private val K_TP_DONE = k("players.tp.done")
        private val K_BRING_DONE = k("players.bring.done")
        private val K_GAMEMODE_DONE = k("players.gamemode.done")
        private val K_KICK_DONE = k("players.kick.done")
        private val K_KICK_SELF = k("players.kick.self")

        private val K_SRV_RELOAD = k("server.reload")
        private val K_SRV_RELOAD_LORE = k("server.reload.lore")
        private val K_SRV_MAINTENANCE = k("server.maintenance")
        private val K_SRV_MAINTENANCE_LORE = k("server.maintenance.lore")
        private val K_SRV_HEALTH = k("server.health")
        private val K_SRV_HEALTH_LORE = k("server.health.lore")
        private val K_RELOAD_OK = k("server.reload.ok")
        private val K_RELOAD_PARTIAL = k("server.reload.partial")
        private val K_MAINTENANCE_ON = k("server.maintenance.on")
        private val K_MAINTENANCE_OFF = k("server.maintenance.off")
        private val K_HEALTH_HEADER = k("server.health.header")
        private val K_HEALTH_LINE = k("server.health.line")

        private val K_SELF_FLY = k("self.fly")
        private val K_SELF_FLY_LORE = k("self.fly.lore")
        private val K_FLY_ON = k("self.fly.on")
        private val K_FLY_OFF = k("self.fly.off")

        private val K_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
