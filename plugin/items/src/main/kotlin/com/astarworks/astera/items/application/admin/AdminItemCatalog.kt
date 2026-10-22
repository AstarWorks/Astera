package com.astarworks.astera.items.application.admin

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
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.resource.ResourceType

/** Admin-catalog grouping of an item (F1-D, ADR-0183). Derived from item data, never from a hard-coded id list. */
public enum class AdminItemCategory(public val key: String) {
    GUN("gun"),
    SWORD("sword"),
    STAFF("staff"),
    SUPPORT("support"),
    OTHER("other"),
    ;

    /** Part of "give full kit" (one of each weapon). Gear and misc items are not. */
    public val isWeapon: Boolean get() = this == GUN || this == SWORD || this == STAFF

    public companion object {
        public fun parse(key: String?): AdminItemCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Pure catalog logic of the admin panel's "Weapons & items" page: which
 * registry items appear, how they group, how they paginate, and the
 * [GuiSpec] for a page. No registry reads here — callers pass the items so
 * the page always mirrors the live content (`/astera reload items` shows up
 * on the next open) and the unit tests need no registry.
 */
public object AdminItemCatalog {

    public const val PAGE_SIZE: Int = 36

    /** Shift-click on an item gives this many. */
    public const val STACK_AMOUNT: Int = 16

    /** Ids with these prefixes are GUI furniture (tiles, panes, indicators), not giveable catalog items. */
    private val HIDDEN_PREFIXES: List<String> = listOf("gui-", "indicator-", "skill-tester")

    public val GUI_ID: GuiId = GuiId("admin-items")

    private val K_TITLE = MessageKey("astera.admin.panel.items.title")
    private val K_TAB_LORE = MessageKey("astera.admin.panel.items.tab.lore")
    private val K_TAB_LORE_GIVE = MessageKey("astera.admin.panel.items.tab.lore-give")
    private val K_KIT = MessageKey("astera.admin.panel.items.kit")
    private val K_KIT_LORE = MessageKey("astera.admin.panel.items.kit.lore")
    private val K_ENTRY_HINT = MessageKey("astera.admin.panel.items.entry.hint")
    private val K_ENTRY_HINT_SHIFT = MessageKey("astera.admin.panel.items.entry.hint-shift")
    private val K_EMPTY = MessageKey("astera.admin.panel.items.empty")

    private val ICON_KIT = ItemId("gui-admin-kit")
    private val ICON_EMPTY = ItemId("gui-admin-empty")
    private val TAB_ICONS: Map<AdminItemCategory?, ItemId> = mapOf(
        null to ItemId("gui-admin-cat-all"),
        AdminItemCategory.GUN to ItemId("gui-admin-cat-gun"),
        AdminItemCategory.SWORD to ItemId("gui-admin-cat-sword"),
        AdminItemCategory.STAFF to ItemId("gui-admin-cat-staff"),
        AdminItemCategory.SUPPORT to ItemId("gui-admin-cat-support"),
        AdminItemCategory.OTHER to ItemId("gui-admin-cat-other"),
    )

    /** Whether [spec] belongs in the catalog at all. */
    public fun isCatalogItem(spec: ItemSpec): Boolean =
        HIDDEN_PREFIXES.none { spec.id.value.startsWith(it) }

    /** Group [spec] by its resource pool / equip slot: ammo = gun, stamina = sword, mana = staff, non-mainhand gear = support. */
    public fun categorize(spec: ItemSpec): AdminItemCategory = when {
        spec.resourceType == ResourceType.Ammo -> AdminItemCategory.GUN
        spec.resourceType == ResourceType.Stamina -> AdminItemCategory.SWORD
        spec.resourceType == ResourceType.Mana -> AdminItemCategory.STAFF
        spec.equipSlot != EquipSlot.MAINHAND -> AdminItemCategory.SUPPORT
        else -> AdminItemCategory.OTHER
    }

    /** Catalog items of [filter] (`null` = all), ordered by category then id. */
    public fun entries(items: Collection<ItemSpec>, filter: AdminItemCategory?): List<ItemSpec> =
        items.filter(::isCatalogItem)
            .filter { filter == null || categorize(it) == filter }
            .sortedWith(compareBy({ categorize(it).ordinal }, { it.id.value }))

    /** One of each weapon (guns, swords, staffs) in the catalog. */
    public fun kit(items: Collection<ItemSpec>): List<ItemSpec> =
        entries(items, null).filter { categorize(it).isWeapon }

    public fun pageCount(entryCount: Int): Int = AdminPanelUi.pageCount(entryCount, PAGE_SIZE)

    public fun pageOf(entries: List<ItemSpec>, page: Int): List<ItemSpec> =
        entries.drop(AdminPanelUi.clampPage(page, pageCount(entries.size)) * PAGE_SIZE).take(PAGE_SIZE)

    /** Build the catalog page (CHEST_54) for [filter] at [requestedPage] over the live [items]. */
    public fun buildSpec(items: Collection<ItemSpec>, filter: AdminItemCategory?, requestedPage: Int): GuiSpec {
        val all = entries(items, null)
        val shown = entries(items, filter)
        val pages = pageCount(shown.size)
        val page = AdminPanelUi.clampPage(requestedPage, pages)
        val filterKey = filter?.key ?: "all"
        return GuiSpec.build(GUI_ID, K_TITLE, GuiSize.CHEST_54) {
            // Row 0: category tabs, kit, back.
            val tabs: List<AdminItemCategory?> = listOf(null) + AdminItemCategory.entries
            tabs.forEachIndexed { i, cat ->
                val count = if (cat == null) all.size else all.count { categorize(it) == cat }
                val selected = cat == filter
                slot(
                    SlotIndex(i),
                    GuiSlot.Static(
                        itemId = TAB_ICONS.getValue(cat),
                        onLeftClick = GuiIntent(
                            INTENT_OPEN,
                            mapOf(PAYLOAD_CATEGORY to (cat?.key ?: "all"), "page" to "0"),
                        ),
                        onShiftClick = cat?.let { GuiIntent(INTENT_GIVE_CATEGORY, mapOf(PAYLOAD_CATEGORY to it.key)) },
                        displayName = GuiText(
                            MessageKey("astera.admin.panel.items.tab.${cat?.key ?: "all"}"),
                            mapOf("count" to count.toString(), "mark" to if (selected) "<yellow>></yellow>" else "<dark_gray>-</dark_gray>"),
                        ),
                        lore = buildList {
                            add(GuiText(K_TAB_LORE))
                            if (cat != null) add(GuiText(K_TAB_LORE_GIVE))
                        },
                    ),
                )
            }
            slot(
                SlotIndex(7),
                GuiSlot.Static(
                    itemId = ICON_KIT,
                    onLeftClick = GuiIntent(INTENT_KIT),
                    displayName = GuiText(K_KIT, mapOf("count" to kit(items).size.toString())),
                    lore = listOf(GuiText(K_KIT_LORE)),
                ),
            )
            slot(SlotIndex(8), AdminPanelUi.back())

            // Rows 1-4: the items themselves, showing the real icon / name / lore.
            val pageEntries = pageOf(shown, page)
            pageEntries.forEachIndexed { i, spec ->
                slot(
                    SlotIndex(9 + i),
                    GuiSlot.Static(
                        itemId = spec.id,
                        onLeftClick = GuiIntent(INTENT_GIVE, mapOf(PAYLOAD_ITEM to spec.id.value, PAYLOAD_AMOUNT to "1")),
                        onShiftClick = GuiIntent(INTENT_GIVE, mapOf(PAYLOAD_ITEM to spec.id.value, PAYLOAD_AMOUNT to STACK_AMOUNT.toString())),
                        lore = listOf(
                            GuiText(K_ENTRY_HINT),
                            GuiText(K_ENTRY_HINT_SHIFT, mapOf("amount" to STACK_AMOUNT.toString())),
                        ),
                    ),
                )
            }
            if (pageEntries.isEmpty()) {
                slot(SlotIndex(22), GuiSlot.Static(itemId = ICON_EMPTY, displayName = GuiText(K_EMPTY)))
            }

            // Row 5: pager + close.
            pager(
                key = INTENT_OPEN,
                payload = mapOf(PAYLOAD_CATEGORY to filterKey),
                page = page,
                pages = pages,
                prevSlot = 48,
                pageSlot = 49,
                nextSlot = 50,
            )
            slot(SlotIndex(53), AdminPanelUi.close())
        }
    }

    public const val INTENT_OPEN: String = "admin.items.open"
    public const val INTENT_GIVE: String = "admin.items.give"
    public const val INTENT_GIVE_CATEGORY: String = "admin.items.give-category"
    public const val INTENT_KIT: String = "admin.items.kit"
    public const val PAYLOAD_CATEGORY: String = "category"
    public const val PAYLOAD_ITEM: String = "item"
    public const val PAYLOAD_AMOUNT: String = "amount"
}
