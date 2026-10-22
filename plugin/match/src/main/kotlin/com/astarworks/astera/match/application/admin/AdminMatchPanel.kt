package com.astarworks.astera.match.application.admin

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.admin.panel.AdminIntents
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.AdminPanelTile
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.services.ServiceRegistry
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
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFormer
import com.astarworks.astera.match.application.usecase.validate.ValidateContentReferencesUseCase
import com.astarworks.astera.match.domain.matchmaking.QueueLane
import com.astarworks.astera.match.domain.matchmaking.QueueSettings
import org.slf4j.LoggerFactory

/**
 * Match-plugin section of the `/astera-admin` panel (F1-D, ADR-0183): active
 * matches with end / abort / next-phase, and the queue lanes with "force
 * start now" / "start with just me". Also contributes the server-page
 * "validate content" action.
 *
 * Owns the `admin.match.` and `admin.validate.` intent prefixes. Actions go
 * through [AdminMatchControlUseCase] / [ForceStartLaneUseCase] (application
 * use cases, not commands).
 */
public class AdminMatchPanel(
    private val guard: AdminPanelGuard,
    private val registry: IAdminPanelRegistry,
    private val guiAdapter: IGuiAdapter,
    private val matches: IMatchRepository,
    private val matchmaker: IMatchmaker,
    /** `null` on a game-only server (no queue lanes there). */
    private val matchFound: MatchFormer?,
    private val settings: () -> QueueSettings,
    private val nowTick: () -> Long,
    private val control: AdminMatchControlUseCase,
    private val forceStart: ForceStartLaneUseCase,
    private val validate: ValidateContentReferencesUseCase?,
    /** `false` on a LOBBY server: matches run on [gameServer], so the match list would mislead. */
    private val hostsMatches: Boolean = true,
    private val gameServer: String = "game",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun install(router: IGuiIntentRouter): List<ServiceRegistry.Registration> {
        val regs = mutableListOf<ServiceRegistry.Registration>()
        regs += registry.register(
            AdminPanelTile(
                id = "match.control",
                point = AdminPanelPoint.HUB,
                order = 20,
                icon = ICON_HUB,
                name = GuiText(K_HUB),
                lore = listOf(GuiText(K_HUB_LORE)),
                intent = GuiIntent(OPEN),
            ),
        )
        regs += router.register(AdminIntents.MATCH_PREFIX, guard.guarded(::handle))
        if (validate != null) {
            regs += registry.register(
                AdminPanelTile(
                    id = "match.validate",
                    point = AdminPanelPoint.SERVER,
                    order = 10,
                    icon = ICON_VALIDATE,
                    name = GuiText(K_VALIDATE),
                    lore = listOf(GuiText(K_VALIDATE_LORE)),
                    intent = GuiIntent(VALIDATE_RUN),
                ),
            )
            regs += router.register(AdminIntents.VALIDATE_PREFIX, guard.guarded(::handleValidate))
        }
        return regs
    }

    /** `admin.match.*` entry point (public for tests). */
    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        var skipRefresh = false
        when (intent.key) {
            OPEN -> refresh(player)
            NEXT -> withMatch(player, intent) { id ->
                when (val r = control.nextPhase(id)) {
                    is Result.Success -> {
                        guard.tell(player, K_NEXT_OK, mapOf("match" to id.value, "phase" to r.value.name.lowercase()))
                        guard.record(player, NEXT, id.value, "phase=${r.value.name.lowercase()}")
                    }
                    is Result.Failure -> guard.tell(player, r.error.messageKey, mapOf("match" to id.value))
                }
            }
            END -> withMatch(player, intent) { id ->
                when (val r = control.end(id)) {
                    is Result.Success -> {
                        guard.tell(player, K_END_OK, mapOf("match" to id.value))
                        guard.record(player, END, id.value)
                    }
                    is Result.Failure -> guard.tell(player, r.error.messageKey, mapOf("match" to id.value))
                }
            }
            ABORT -> withMatch(player, intent) { id ->
                when (val r = control.abort(id)) {
                    is Result.Success -> {
                        guard.tell(player, K_ABORT_OK, mapOf("match" to id.value))
                        guard.record(player, ABORT, id.value)
                    }
                    is Result.Failure -> guard.tell(player, r.error.messageKey, mapOf("match" to id.value))
                }
            }
            LANE_FORCE -> withLane(player, intent) { lane ->
                when (val r = forceStart.forceStart(lane)) {
                    is Result.Success -> {
                        guard.tell(player, K_FORCE_OK, mapOf("lane" to lane, "match" to r.value.value))
                        guard.record(player, LANE_FORCE, lane, "match=${r.value.value}")
                    }
                    is Result.Failure -> guard.tell(player, r.error.messageKey, mapOf("lane" to lane))
                }
            }
            LANE_SOLO -> withLane(player, intent) { lane ->
                when (val r = forceStart.startWithOnly(lane, player.id)) {
                    is Result.Success -> {
                        guard.tell(player, K_SOLO_OK, mapOf("lane" to lane, "match" to r.value.value))
                        guard.record(player, LANE_SOLO, lane, "match=${r.value.value}")
                        // The admin was teleported into the arena; the panel would only be in the way.
                        guiAdapter.close(player)
                        skipRefresh = true
                    }
                    is Result.Failure -> guard.tell(player, r.error.messageKey, mapOf("lane" to lane))
                }
            }
            else -> {
                log.warn("Unknown admin.match intent '{}' from {}", intent.key, player.name)
                return
            }
        }
        if (intent.key != OPEN && !skipRefresh) refresh(player)
    }

    private fun handleValidate(intent: GuiIntent, player: IMcPlayer) {
        val uc = validate ?: return
        if (intent.key != VALIDATE_RUN) return
        val report = uc.validate()
        guard.tell(
            player,
            if (report.ok) K_VALIDATE_OK else K_VALIDATE_ISSUES,
            mapOf(
                "categories" to report.checkedCategories.size.toString(),
                "issues" to report.issues.size.toString(),
            ),
        )
        report.issues.take(MAX_ISSUE_LINES).forEach { guard.tell(player, K_VALIDATE_LINE, mapOf("issue" to it.toString())) }
        guard.record(player, VALIDATE_RUN, detail = "issues=${report.issues.size}")
    }

    private fun refresh(player: IMcPlayer) {
        if (!AdminPanelUi.show(guiAdapter, player, buildSpec())) guard.tell(player, K_OPEN_REJECTED)
    }

    private fun withMatch(player: IMcPlayer, intent: GuiIntent, block: (MatchId) -> Unit) {
        val id = intent.payload[PAYLOAD_MATCH]?.takeIf { it.isNotBlank() }?.let { runCatching { MatchId(it) }.getOrNull() }
        if (id == null) guard.tell(player, K_BAD_MATCH) else block(id)
    }

    private fun withLane(player: IMcPlayer, intent: GuiIntent, block: (String) -> Unit) {
        val lane = intent.payload[PAYLOAD_LANE]
        if (lane.isNullOrBlank()) guard.tell(player, K_BAD_LANE) else block(lane)
    }

    /** The match-control page: active matches (rows 0-2), queue lanes (row 4). */
    public fun buildSpec(): GuiSpec {
        val config = settings()
        val tick = nowTick()
        val active = matches.findActiveMatches().sortedBy { it.id.value }
        val lanes = config.lanes.values.sortedBy { it.key }
        return GuiSpec.build(GUI_ID, K_TITLE, GuiSize.CHEST_54) {
            if (!hostsMatches) {
                slot(
                    SlotIndex(13),
                    GuiSlot.Static(
                        ICON_EMPTY,
                        displayName = GuiText(K_REMOTE_MATCHES, mapOf("game" to gameServer)),
                        lore = listOf(GuiText(K_REMOTE_MATCHES_LORE)),
                    ),
                )
            } else {
                if (active.isEmpty()) {
                    slot(SlotIndex(13), GuiSlot.Static(ICON_EMPTY, displayName = GuiText(K_NO_MATCHES)))
                }
                active.take(MATCH_SLOTS).forEachIndexed { i, m -> slot(SlotIndex(i), matchTile(m, config, tick)) }
            }
            if (matchFound != null) {
                slot(SlotIndex(27), GuiSlot.Static(ICON_LANES, displayName = GuiText(K_LANES_HEADER)))
                lanes.take(LANE_SLOTS).forEachIndexed { i, lane -> slot(SlotIndex(36 + i), laneTile(lane, matchFound)) }
            }
            slot(SlotIndex(45), AdminPanelUi.back())
            slot(SlotIndex(49), AdminPanelUi.refresh(GuiIntent(OPEN)))
            slot(SlotIndex(53), AdminPanelUi.close())
        }
    }

    private fun matchTile(m: Match, config: QueueSettings, tick: Long): GuiSlot.Static {
        val lane = config.lanes.values.firstOrNull { it.stage == m.stageId }?.key ?: "-"
        val players = m.teams.sumOf { it.members.size }
        val elapsed = ((tick - m.startedAtTick) / TICKS_PER_SECOND).coerceAtLeast(0)
        val payload = mapOf(PAYLOAD_MATCH to m.id.value)
        return GuiSlot.Static(
            itemId = ICON_MATCH,
            onLeftClick = GuiIntent(NEXT, payload),
            onRightClick = GuiIntent(END, payload),
            onShiftClick = GuiIntent(ABORT, payload),
            displayName = GuiText(K_MATCH_ROW, mapOf("match" to m.id.value, "mode" to lane)),
            lore = listOf(
                GuiText(K_MATCH_STAGE, mapOf("stage" to m.stageId.value)),
                GuiText(K_MATCH_PHASE, mapOf("phase" to m.phase.name.lowercase())),
                GuiText(K_MATCH_PLAYERS, mapOf("players" to players.toString())),
                GuiText(K_MATCH_TIME, mapOf("time" to "%d:%02d".format(elapsed / 60, elapsed % 60))),
                GuiText(K_MATCH_HINT_NEXT),
                GuiText(K_MATCH_HINT_END),
                GuiText(K_MATCH_HINT_ABORT),
            ),
        )
    }

    private fun laneTile(lane: QueueLane, former: MatchFormer): GuiSlot.Static {
        val queued = matchmaker.pendingByMode(lane.mode).size
        val busy = former.isStageBusy(lane.mode)
        val payload = mapOf(PAYLOAD_LANE to lane.key)
        return GuiSlot.Static(
            itemId = ICON_LANE,
            onLeftClick = GuiIntent(LANE_FORCE, payload),
            onRightClick = GuiIntent(LANE_SOLO, payload),
            displayName = GuiText(
                K_LANE_ROW,
                mapOf("lane" to lane.key, "queued" to queued.toString(), "min" to lane.minPlayers.toString()),
            ),
            lore = listOf(
                GuiText(K_LANE_STAGE, mapOf("stage" to lane.stage.value, "capacity" to lane.capacity.toString())),
                GuiText(if (busy) K_LANE_BUSY else K_LANE_FREE),
                GuiText(K_LANE_HINT_FORCE),
                GuiText(K_LANE_HINT_SOLO),
            ),
        )
    }

    public companion object {
        public const val OPEN: String = "admin.match.open"
        public const val NEXT: String = "admin.match.next"
        public const val END: String = "admin.match.end"
        public const val ABORT: String = "admin.match.abort"
        public const val LANE_FORCE: String = "admin.match.lane-force"
        public const val LANE_SOLO: String = "admin.match.lane-solo"
        public const val VALIDATE_RUN: String = "admin.validate.run"
        public const val PAYLOAD_MATCH: String = "match"
        public const val PAYLOAD_LANE: String = "lane"

        public val GUI_ID: GuiId = GuiId("admin-match")

        private const val MATCH_SLOTS: Int = 27
        private const val LANE_SLOTS: Int = 9
        private const val TICKS_PER_SECOND: Long = 20L
        private const val MAX_ISSUE_LINES: Int = 5

        private val ICON_HUB = ItemId("gui-admin-match-control")
        private val ICON_MATCH = ItemId("gui-admin-match")
        private val ICON_LANE = ItemId("gui-admin-lane")
        private val ICON_LANES = ItemId("gui-admin-lanes")
        private val ICON_EMPTY = ItemId("gui-admin-empty")
        private val ICON_VALIDATE = ItemId("gui-admin-validate")

        private fun k(s: String) = MessageKey("astera.admin.panel.match.$s")

        private val K_TITLE = k("title")
        private val K_HUB = k("hub")
        private val K_HUB_LORE = k("hub.lore")
        private val K_NO_MATCHES = k("none")
        private val K_REMOTE_MATCHES = k("remote")
        private val K_REMOTE_MATCHES_LORE = k("remote.lore")
        private val K_LANES_HEADER = k("lanes")
        private val K_MATCH_ROW = k("row")
        private val K_MATCH_STAGE = k("row.stage")
        private val K_MATCH_PHASE = k("row.phase")
        private val K_MATCH_PLAYERS = k("row.players")
        private val K_MATCH_TIME = k("row.time")
        private val K_MATCH_HINT_NEXT = k("row.hint-next")
        private val K_MATCH_HINT_END = k("row.hint-end")
        private val K_MATCH_HINT_ABORT = k("row.hint-abort")
        private val K_LANE_ROW = k("lane")
        private val K_LANE_STAGE = k("lane.stage")
        private val K_LANE_BUSY = k("lane.busy")
        private val K_LANE_FREE = k("lane.free")
        private val K_LANE_HINT_FORCE = k("lane.hint-force")
        private val K_LANE_HINT_SOLO = k("lane.hint-solo")
        private val K_NEXT_OK = k("next.ok")
        private val K_END_OK = k("end.ok")
        private val K_ABORT_OK = k("abort.ok")
        private val K_FORCE_OK = k("force.ok")
        private val K_SOLO_OK = k("solo.ok")
        private val K_BAD_MATCH = k("err.bad-match")
        private val K_BAD_LANE = k("err.bad-lane")
        private val K_VALIDATE = k("validate")
        private val K_VALIDATE_LORE = k("validate.lore")
        private val K_VALIDATE_OK = k("validate.ok")
        private val K_VALIDATE_ISSUES = k("validate.issues")
        private val K_VALIDATE_LINE = k("validate.line")
        private val K_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
