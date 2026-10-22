package com.astarworks.astera.items.application.admin

import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.items.testfixtures.itemSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdminItemCatalogTest {

    private fun gun(id: String) = itemSpec(id).copy(resourceType = ResourceType.Ammo)
    private fun sword(id: String) = itemSpec(id).copy(resourceType = ResourceType.Energy)
    private fun staff(id: String) = itemSpec(id, template = ItemTemplate.Vanilla(material = "BLAZE_ROD")).copy(resourceType = ResourceType.Energy)
    private fun gear(id: String) = itemSpec(id, equipSlot = EquipSlot.HELMET)
    private fun misc(id: String) = itemSpec(id)

    private val content: List<ItemSpec> = listOf(
        gun("rifle"), gun("pistol"), sword("greatsword"), staff("fire-staff"), gear("iron-helm"), misc("apple"),
        // GUI furniture must never show up as a giveable item.
        misc("gui-tile-queue"), misc("gui-admin-back"), misc("indicator-on"),
    )

    @Test
    fun `categorize derives the group from resource pool and equip slot`() {
        assertThat(AdminItemCatalog.categorize(gun("g"))).isEqualTo(AdminItemCategory.GUN)
        assertThat(AdminItemCatalog.categorize(sword("s"))).isEqualTo(AdminItemCategory.SWORD)
        assertThat(AdminItemCatalog.categorize(staff("t"))).isEqualTo(AdminItemCategory.STAFF)
        assertThat(AdminItemCatalog.categorize(gear("h"))).isEqualTo(AdminItemCategory.SUPPORT)
        assertThat(AdminItemCatalog.categorize(misc("m"))).isEqualTo(AdminItemCategory.OTHER)
    }

    @Test
    fun `entries hide gui furniture and order by category then id`() {
        val all = AdminItemCatalog.entries(content, null).map { it.id.value }
        assertThat(all).containsExactly("pistol", "rifle", "greatsword", "fire-staff", "iron-helm", "apple")
        assertThat(AdminItemCatalog.entries(content, AdminItemCategory.GUN).map { it.id.value })
            .containsExactly("pistol", "rifle")
    }

    @Test
    fun `kit is one of each weapon and excludes gear and misc`() {
        assertThat(AdminItemCatalog.kit(content).map { it.id.value })
            .containsExactly("pistol", "rifle", "greatsword", "fire-staff")
    }

    @Test
    fun `pagination slices 36 per page and clamps out-of-range pages`() {
        val many = (1..80).map { misc("item-%03d".format(it)) }
        assertThat(AdminItemCatalog.pageCount(many.size)).isEqualTo(3)
        assertThat(AdminItemCatalog.pageCount(0)).isEqualTo(1)
        val entries = AdminItemCatalog.entries(many, null)
        assertThat(AdminItemCatalog.pageOf(entries, 0)).hasSize(36)
        assertThat(AdminItemCatalog.pageOf(entries, 2)).hasSize(8)
        assertThat(AdminItemCatalog.pageOf(entries, 99).first().id.value).isEqualTo("item-073")
    }

    @Test
    fun `spec shows real items with give intents and a working pager`() {
        val many = (1..40).map { misc("item-%03d".format(it)) }
        val spec = AdminItemCatalog.buildSpec(many, AdminItemCategory.OTHER, 0)

        val first = spec.slotAt(SlotIndex(9)) as GuiSlot.Static
        assertThat(first.itemId.value).isEqualTo("item-001")
        assertThat(first.onLeftClick!!.key).isEqualTo(AdminItemCatalog.INTENT_GIVE)
        assertThat(first.onLeftClick!!.payload).containsEntry("item", "item-001").containsEntry("amount", "1")
        assertThat(first.onShiftClick!!.payload).containsEntry("amount", AdminItemCatalog.STACK_AMOUNT.toString())

        // Page 0 has no prev, but has next to page 1.
        assertThat(spec.slotAt(SlotIndex(48))).isEqualTo(GuiSlot.Empty)
        val next = spec.slotAt(SlotIndex(50)) as GuiSlot.Static
        assertThat(next.onLeftClick!!.payload).containsEntry("page", "1").containsEntry("category", "other")

        // Page 1 holds the remaining 4 items.
        val page1 = AdminItemCatalog.buildSpec(many, AdminItemCategory.OTHER, 1)
        assertThat((page1.slotAt(SlotIndex(9)) as GuiSlot.Static).itemId.value).isEqualTo("item-037")
        assertThat(page1.slotAt(SlotIndex(13))).isEqualTo(GuiSlot.Empty)
    }

    @Test
    fun `tabs carry counts and category shift-click gives the category`() {
        val spec = AdminItemCatalog.buildSpec(content, null, 0)
        val gunTab = spec.slotAt(SlotIndex(1)) as GuiSlot.Static
        assertThat(gunTab.displayName!!.args).containsEntry("count", "2")
        assertThat(gunTab.onShiftClick!!.key).isEqualTo(AdminItemCatalog.INTENT_GIVE_CATEGORY)
        assertThat(gunTab.onShiftClick!!.payload).containsEntry("category", "gun")
        val kit = spec.slotAt(SlotIndex(7)) as GuiSlot.Static
        assertThat(kit.onLeftClick!!.key).isEqualTo(AdminItemCatalog.INTENT_KIT)
        assertThat(kit.displayName!!.args).containsEntry("count", "4")
    }
}
