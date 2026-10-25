package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.guidepth.GuiModalSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import org.slf4j.LoggerFactory

/**
 * Wave M 5polish-quickwin (ADR-0130 §C) — quickbar (hotbar) skill binding UI.
 *
 * # Intent shape
 *
 * Two-step flow under the shared `lobby.hotbar.bind.` prefix:
 *
 * 1. **Open**: `lobby.hotbar.bind.<N>` (N is the hotbar slot 1..9) — opens a
 *    [GuiModalSpec.QuickbarSelector] modal listing every known skill id (passed
 *    in at construction so this handler stays oblivious to
 *    [com.astarworks.astera.combat.application.port.outbound.ISkillRegistry]
 *    — ADR-0022 sibling isolation: core cannot import combat).
 * 2. **Commit**: `lobby.hotbar.bind.commit.<N>` — modal `onSelect` callback;
 *    payload entry `skillId` carries the chosen skill content-id. Writes via
 *    [IPlayerSettingsRepository.upsert].
 *
 * # Skill id source
 *
 * The handler receives a `() -> List<String>` [skillIdsProvider] at
 * construction. Phase C wiring resolves combat's `ISkillRegistry` lazily
 * and exposes `it.all().map { it.id.value }`; this handler never imports
 * combat directly.
 *
 * # Empty payload guard
 *
 * If the commit intent arrives without a `skillId` payload entry the
 * handler logs WARN and drops the message (no settings write). The modal
 * adapter is expected to always supply the entry on the player's chosen
 * slot click — the guard is a belt-and-braces against future adapter
 * regressions.
 */
public class BindQuickbarSlotIntentHandler(
    private val modalGateway: IGuiModalGateway,
    private val settings: IPlayerSettingsRepository,
    private val skillIdsProvider: () -> List<String>,
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        val key = intent.key
        if (!key.startsWith(INTENT_PREFIX)) {
            log.warn("BindQuickbarSlotIntentHandler dispatched a non-matching intent '{}'.", key)
            return
        }
        val rest = key.removePrefix(INTENT_PREFIX)
        if (rest.startsWith(COMMIT_INFIX)) {
            handleCommit(rest.removePrefix(COMMIT_INFIX), intent, player)
        } else {
            handleOpen(rest, player)
        }
    }

    private fun handleOpen(slotToken: String, player: IMcPlayer) {
        val slot = slotToken.toIntOrNull()
        if (slot == null || slot !in 1..9) {
            log.warn(
                "lobby.hotbar.bind open: invalid slot '{}' (expected 1..9) from {}",
                slotToken, player.id,
            )
            return
        }
        val skillIds = skillIdsProvider()
        if (skillIds.isEmpty()) {
            // No skills to bind — surface a chat hint and bail.
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_NO_SKILLS, emptyMap()),
            )
            return
        }
        // QuickbarSelector caps at 18 slots (GuiModalSpec); take the first 18
        // and let pagination land in a follow-up wave.
        val capped = skillIds.take(GuiModalSpec.QuickbarSelector.MAX_QUICKBAR_SLOTS)
        val slots: List<GuiSlot> = capped.map { id ->
            GuiSlot.Static(
                itemId = ItemId(SKILL_TILE_ITEM),
                onLeftClick = GuiIntent(
                    key = "$INTENT_PREFIX$COMMIT_INFIX$slot",
                    payload = mapOf(PAYLOAD_SKILL_ID to id),
                ),
                tooltip = listOf(MessageKey("astera.hotbar.bind.skill.tooltip")),
            )
        }
        val modal = GuiModalSpec.QuickbarSelector(
            slots = slots,
            onSelect = GuiIntent("$INTENT_PREFIX$COMMIT_INFIX$slot"),
        )
        val result = modalGateway.showModal(player.id, modal)
        if (result is Result.Failure) {
            log.debug(
                "Quickbar selector modal failed for {}: {}",
                player.id, result.error,
            )
        }
    }

    private fun handleCommit(slotToken: String, intent: GuiIntent, player: IMcPlayer) {
        val slot = slotToken.toIntOrNull()
        if (slot == null || slot !in 1..9) {
            log.warn(
                "lobby.hotbar.bind commit: invalid slot '{}' (expected 1..9) from {}",
                slotToken, player.id,
            )
            return
        }
        val skillId = intent.payload[PAYLOAD_SKILL_ID]
        if (skillId.isNullOrBlank()) {
            log.warn(
                "lobby.hotbar.bind commit: missing/blank skillId payload from {} for slot {}",
                player.id, slot,
            )
            return
        }
        val current = settings.findOrDefault(player.id)
        val updated = current.copy(quickCastSlots = current.quickCastSlots + (slot to skillId))
        settings.upsert(updated)
        playerGateway.sendMessage(
            player.id,
            messages.render(
                player.id,
                MSG_COMMIT_OK,
                mapOf("slot" to slot.toString(), "skill" to skillId),
            ),
        )
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.hotbar.bind."
        public const val COMMIT_INFIX: String = "commit."
        public const val PAYLOAD_SKILL_ID: String = "skillId"
        public const val SKILL_TILE_ITEM: String = "gui-tile-skill"

        private val MSG_NO_SKILLS = MessageKey("astera.hotbar.bind.no-skills")
        private val MSG_COMMIT_OK = MessageKey("astera.hotbar.bind.commit-ok")

        // Slot index 0 — reserved for SlotIndex(0) compile reference if the
        // adapter ever needs to render the modal as a CHEST_9 placeholder. The
        // QuickbarSelector dispatcher itself uses [GuiSlot] without
        // SlotIndex; this stays here for future tests.
        @Suppress("unused")
        public val PLACEHOLDER_SLOT: SlotIndex = SlotIndex(0)
    }
}
