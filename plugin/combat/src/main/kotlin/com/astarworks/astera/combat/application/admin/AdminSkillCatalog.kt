package com.astarworks.astera.combat.application.admin

import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi.pager
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Pure catalog logic of the admin panel's "Skills" page (F2-5, ADR-0187): every
 * registered skill as an icon (by archetype), click = cast, shift-click = give a
 * skill tester item. Callers pass the live skills so a content reload shows up on
 * the next open.
 */
public object AdminSkillCatalog {

    public const val PAGE_SIZE: Int = 36

    public val GUI_ID: GuiId = GuiId("admin-skills")

    public const val INTENT_OPEN: String = "admin.skills.open"
    public const val INTENT_CAST: String = "admin.skills.cast"
    public const val INTENT_TESTER: String = "admin.skills.tester"
    public const val PAYLOAD_ARCHETYPE: String = "archetype"
    public const val PAYLOAD_SKILL: String = "skill"

    private val K_TITLE = MessageKey("astera.admin.panel.skills.title")
    private val K_TAB_LORE = MessageKey("astera.admin.panel.skills.tab.lore")
    private val K_ENTRY_NAME = MessageKey("astera.admin.panel.skills.entry.name")
    private val K_ENTRY_INFO = MessageKey("astera.admin.panel.skills.entry.info")
    private val K_ENTRY_HINT = MessageKey("astera.admin.panel.skills.entry.hint")
    private val K_ENTRY_HINT_SHIFT = MessageKey("astera.admin.panel.skills.entry.hint-shift")
    private val K_EMPTY = MessageKey("astera.admin.panel.skills.empty")

    private val ICON_ALL = ItemId("gui-admin-skills")
    private val ICON_EMPTY = ItemId("gui-admin-empty")

    /** Panel icon of [archetype]; plain items `gui-skill-<archetype>` in `content/items/`. */
    public fun iconOf(archetype: SkillArchetype): ItemId = ItemId("gui-skill-${archetype.name.lowercase()}")

    public fun parse(key: String?): SkillArchetype? = SkillArchetype.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }

    /** Skills of [filter] (`null` = all), ordered by archetype then id. */
    public fun entries(skills: Collection<SkillSpec>, filter: SkillArchetype?): List<SkillSpec> =
        skills.filter { filter == null || it.archetype == filter }
            .sortedWith(compareBy({ it.archetype.ordinal }, { it.id.value }))

    public fun pageCount(entryCount: Int): Int = AdminPanelUi.pageCount(entryCount, PAGE_SIZE)

    public fun pageOf(entries: List<SkillSpec>, page: Int): List<SkillSpec> =
        entries.drop(AdminPanelUi.clampPage(page, pageCount(entries.size)) * PAGE_SIZE).take(PAGE_SIZE)

    /** Build the Skills page (CHEST_54) for [filter] at [requestedPage]. */
    public fun buildSpec(skills: Collection<SkillSpec>, filter: SkillArchetype?, requestedPage: Int): GuiSpec {
        val shown = entries(skills, filter)
        val pages = pageCount(shown.size)
        val page = AdminPanelUi.clampPage(requestedPage, pages)
        val filterKey = filter?.name?.lowercase() ?: "all"
        return GuiSpec.build(GUI_ID, K_TITLE, GuiSize.CHEST_54) {
            val tabs: List<SkillArchetype?> = listOf(null) + SkillArchetype.entries
            tabs.forEachIndexed { i, archetype ->
                val count = if (archetype == null) skills.size else skills.count { it.archetype == archetype }
                slot(
                    SlotIndex(i),
                    GuiSlot.Static(
                        itemId = archetype?.let(::iconOf) ?: ICON_ALL,
                        onLeftClick = GuiIntent(
                            INTENT_OPEN,
                            mapOf(PAYLOAD_ARCHETYPE to (archetype?.name?.lowercase() ?: "all"), "page" to "0"),
                        ),
                        displayName = GuiText(
                            MessageKey("astera.admin.panel.skills.tab.${archetype?.name?.lowercase() ?: "all"}"),
                            mapOf(
                                "count" to count.toString(),
                                "mark" to if (archetype == filter) "<yellow>></yellow>" else "<dark_gray>-</dark_gray>",
                            ),
                        ),
                        lore = listOf(GuiText(K_TAB_LORE)),
                    ),
                )
            }
            slot(SlotIndex(8), AdminPanelUi.back())

            val pageEntries = pageOf(shown, page)
            pageEntries.forEachIndexed { i, skill ->
                slot(
                    SlotIndex(9 + i),
                    GuiSlot.Static(
                        itemId = iconOf(skill.archetype),
                        onLeftClick = GuiIntent(INTENT_CAST, mapOf(PAYLOAD_SKILL to skill.id.value)),
                        onShiftClick = GuiIntent(INTENT_TESTER, mapOf(PAYLOAD_SKILL to skill.id.value)),
                        displayName = GuiText(K_ENTRY_NAME, mapOf("id" to skill.id.value)),
                        lore = listOf(
                            GuiText(
                                K_ENTRY_INFO,
                                mapOf(
                                    "archetype" to skill.archetype.name.lowercase(),
                                    "cooldown" to skill.cooldownTicks.toString(),
                                ),
                            ),
                            GuiText(K_ENTRY_HINT),
                            GuiText(K_ENTRY_HINT_SHIFT),
                        ),
                    ),
                )
            }
            if (pageEntries.isEmpty()) {
                slot(SlotIndex(22), GuiSlot.Static(itemId = ICON_EMPTY, displayName = GuiText(K_EMPTY)))
            }

            pager(
                key = INTENT_OPEN,
                payload = mapOf(PAYLOAD_ARCHETYPE to filterKey),
                page = page,
                pages = pages,
                prevSlot = 48,
                pageSlot = 49,
                nextSlot = 50,
            )
            slot(SlotIndex(53), AdminPanelUi.close())
        }
    }
}
