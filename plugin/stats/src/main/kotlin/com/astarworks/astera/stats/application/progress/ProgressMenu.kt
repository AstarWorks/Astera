package com.astarworks.astera.stats.application.progress

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.norma.PeriodKind
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.INormaService
import java.time.Clock
import java.time.LocalDate

/**
 * UI-04 - the Daily / Quests screen behind the main menu tile (`progress.open`): today's login bonus (click to
 * collect), the daily / weekly / seasonal missions with progress, and the quest chains with their current stage.
 * `/astera daily|missions|quests` stay as chat shortcuts for the same data.
 *
 * Layout (CHEST_54): slot 4 login bonus; slot 9 missions header + 10..26 missions; slot 27 quests header +
 * 28..44 quests; 45 back, 53 close. Rows past the budget are dropped (the catalogs are far smaller).
 */
internal class ProgressMenu(
    private val loginBonus: ILoginBonusService,
    /** Collects and announces today's bonus; `false` when there was nothing to collect. */
    private val claimDaily: (PlayerId) -> Boolean,
    private val normas: INormaService,
    private val quests: IQuestService,
    private val questChains: IQuestChainRegistry,
    private val adapter: IGuiAdapter,
    private val server: IMcServer,
    private val messages: IMessageRenderer,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_CLAIM -> claimDaily(player.id)
            INTENT_OPEN -> Unit
            else -> return
        }
        open(player.id)
    }

    /** Opens the screen, or repaints it when it is already the open one. */
    fun open(playerId: PlayerId): Boolean {
        val player = server.findPlayer(playerId) ?: return false
        val spec = build(playerId)
        return if (adapter.currentSpec(player)?.id == GUI_ID) {
            adapter.update(player, spec)
            true
        } else {
            adapter.open(player, spec)
        }
    }

    private fun text(key: String, args: Map<String, String> = emptyMap()) = GuiText(MessageKey("astera.progress.gui.$key"), args)

    private fun build(playerId: PlayerId): GuiSpec = GuiSpec.build(
        id = GUI_ID,
        title = MessageKey("astera.progress.gui.title"),
        size = GuiSize.CHEST_54,
    ) {
        slot(SlotIndex(DAILY_SLOT), dailyTile(playerId))
        slot(SlotIndex(MISSIONS_HEADER), header("missions.header", MISSION_ICON))
        val missions = normas.views(playerId)
            .sortedWith(compareBy({ it.norma.period.ordinal }, { it.norma.id.value }))
            .take(MISSIONS_BUDGET)
        if (missions.isEmpty()) {
            slot(SlotIndex(MISSIONS_HEADER + 1), GuiSlot.Static(itemId = PANE, displayName = text("missions.none")))
        }
        missions.forEachIndexed { i, view ->
            val period = messages.render(playerId, MessageKey("astera.progress.period.${periodToken(view.norma.period)}"))
            slot(
                SlotIndex(MISSIONS_HEADER + 1 + i),
                GuiSlot.Static(
                    itemId = if (view.completed) DONE_ICON else MISSION_ICON,
                    displayName = GuiText(view.norma.displayKey),
                    lore = listOf(
                        text("mission.period", mapOf("period" to period)),
                        if (view.completed) text("mission.done")
                        else text("mission.progress", mapOf("current" to view.current.coerceAtMost(view.required).toString(), "required" to view.required.toString())),
                    ),
                ),
            )
        }
        slot(SlotIndex(QUESTS_HEADER), header("quests.header", QUEST_ICON))
        val rows = quests.allChains(playerId).sortedBy { it.chainId.value }
        var next = QUESTS_HEADER + 1
        for (row in rows) {
            if (next >= QUESTS_HEADER + 1 + QUESTS_BUDGET) break
            val chain = questChains.find(row.chainId) ?: continue
            val lore = if (row.isComplete) {
                listOf(text("quest.complete"))
            } else {
                val stage = chain.stage(row.currentStageId) ?: continue
                listOf(
                    GuiText(stage.titleKey),
                    text("quest.progress", mapOf("current" to row.stageProgress.toString(), "required" to questStageTarget(stage.criteria).toString())),
                )
            }
            slot(
                SlotIndex(next++),
                GuiSlot.Static(itemId = if (row.isComplete) DONE_ICON else QUEST_ICON, displayName = GuiText(chain.titleKey), lore = lore),
            )
        }
        if (next == QUESTS_HEADER + 1) {
            slot(SlotIndex(next), GuiSlot.Static(itemId = PANE, displayName = text("quests.none")))
        }
        slot(
            SlotIndex(BACK_SLOT),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-back"),
                onLeftClick = GuiIntent("gui.back", mapOf("gui" to "main-menu", "in_match" to "match-menu")),
                tooltip = listOf(MessageKey("astera.gui.button.back")),
            ),
        )
        slot(
            SlotIndex(CLOSE_SLOT),
            GuiSlot.Static(
                itemId = ItemId("gui-pane-red"),
                onLeftClick = GuiIntent("gui.close"),
                tooltip = listOf(MessageKey("astera.gui.button.close")),
            ),
        )
    }

    private fun header(key: String, icon: ItemId) = GuiSlot.Static(itemId = icon, displayName = text(key))

    private fun dailyTile(playerId: PlayerId): GuiSlot.Static {
        val status = loginBonus.status(playerId, LocalDate.now(clock))
        val lore = mutableListOf(text("daily.status", mapOf("streak" to status.streakDays.toString(), "day" to status.day.toString())))
        status.reward?.let { lore += text("daily.reward", mapOf("amount" to it.amount.toString(), "currency" to it.currency.name)) }
        val canClaim = !status.claimedToday && status.reward != null
        lore += text(if (status.claimedToday) "daily.claimed" else if (canClaim) "daily.claim" else "daily.none")
        status.tomorrow?.let { lore += text("daily.tomorrow", mapOf("amount" to it.amount.toString(), "currency" to it.currency.name)) }
        return GuiSlot.Static(
            itemId = DAILY_ICON,
            displayName = text("daily.name"),
            lore = lore,
            onLeftClick = if (canClaim) GuiIntent(INTENT_CLAIM) else null,
        )
    }

    private fun periodToken(period: PeriodKind): String = period.name.lowercase()

    companion object {
        const val INTENT_PREFIX: String = "progress."
        const val INTENT_OPEN: String = "progress.open"
        const val INTENT_CLAIM: String = "progress.claim"

        val GUI_ID: GuiId = GuiId("progress")

        const val DAILY_SLOT: Int = 4
        const val MISSIONS_HEADER: Int = 9
        const val MISSIONS_BUDGET: Int = 17
        const val QUESTS_HEADER: Int = 27
        const val QUESTS_BUDGET: Int = 17
        const val BACK_SLOT: Int = 45
        const val CLOSE_SLOT: Int = 53

        private val DAILY_ICON = ItemId("gui-tile-daily")
        private val MISSION_ICON = ItemId("gui-tile-mission")
        private val DONE_ICON = ItemId("gui-tile-mission-done")
        private val QUEST_ICON = ItemId("gui-tile-quests")
        private val PANE = ItemId("gui-pane-dark")
    }
}
