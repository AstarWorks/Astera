package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.service.item.ItemBlueprintFactory
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.items.application.admin.AdminItemCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * ADR-0241: an Astera item looks the same everywhere. The admin catalog icon of an item is the stack
 * `/astera items give` hands out (name, lore, `item_model`, material, cooldown group, ...) - GUI slots only
 * append their own lore lines - and every `gui-*` tile gets its generated art through that same path.
 */
class ItemIconParityScenarioTest {

    /** `gui-*` items that deliberately keep a vanilla look (fillers, player heads); mirrors item-art's `Icons.VANILLA`. */
    private val vanillaLook = setOf(
        "gui-pane-dark", "gui-pane-red", "gui-admin-empty",
        "gui-admin-player-head", "gui-admin-players", "gui-tile-profile", "gui-tile-training",
    ) + setOf(
        // UI-01..05 tiles (docs/game-design/access-map.md): vanilla look until item-art draws their glyphs.
        "gui-tile-achievements", "gui-tile-quests", "gui-tile-help", "gui-tile-language", "gui-tile-volume-music",
        "gui-tile-volume-sound", "gui-tile-volume-voice", "gui-tile-controls", "gui-tile-quickcast",
        "gui-tile-particles", "gui-tile-double-jump", "gui-tile-privacy", "gui-tile-back", "gui-tile-cosmetics",
    )

    @Test
    fun `every admin catalog icon is the blueprint of the item the give command delivers`() {
        scenario("item-icon-parity-catalog") {
            val admin = player("admin")
            val r = sim.services.getOrThrow<IPermissionService>().assign(admin.id, RoleId("admin"))
            check(r is Result.Success) { "assign admin: $r" }
            val view = checkNotNull(sim.server.view(admin.id))
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val adapter = sim.services.getOrThrow<IGuiAdapter>()
            val registry = sim.services.getOrThrow<IItemRegistry>()
            val blueprints = sim.services.getOrThrow<ItemBlueprintFactory>()

            val iconIds = linkedSetOf<String>()
            var page = 0
            while (true) {
                router.dispatch(
                    view,
                    GuiIntent(AdminItemCatalog.INTENT_OPEN, mapOf(AdminItemCatalog.PAYLOAD_CATEGORY to "all", "page" to page.toString())),
                )
                val spec = checkNotNull(adapter.currentSpec(view)) { "catalog page $page did not open" }
                val itemSlots = spec.slots.values.filterIsInstance<GuiSlot.Static>()
                    .filter { it.onLeftClick?.key == AdminItemCatalog.INTENT_GIVE }
                for (slot in itemSlots) {
                    // The icon is the item itself: no name override, only appended lore extras.
                    assertThat(slot.displayName).`as`("name override on ${slot.itemId.value}").isNull()
                    iconIds += slot.itemId.value
                }
                if (itemSlots.size < AdminItemCatalog.PAGE_SIZE) break
                page++
            }
            assertThat(iconIds).`as`("catalog lists the registry's items").isNotEmpty()

            sim.itemDelivery.delivered.clear()
            for (id in iconIds) check(sim.give(admin, id)) { "give $id failed" }
            val delivered = sim.itemDelivery.delivered.associateBy { it.id.value }
            assertThat(delivered.keys).containsExactlyInAnyOrderElementsOf(iconIds)

            for (id in iconIds) {
                val iconSpec = checkNotNull(registry.find(ItemId(id)))
                val icon = blueprints.build(iconSpec, admin.id)
                val given = blueprints.build(delivered.getValue(id), admin.id)
                assertThat(icon).`as`("icon vs given item $id").isEqualTo(given)
                assertThat(given.itemId).isEqualTo(id)
                assertThat(given.cooldownGroup != null)
                    .`as`("use_cooldown group of $id iff it binds a skill")
                    .isEqualTo(iconSpec.skillBindings.isNotEmpty())
            }
        }
    }

    @Test
    fun `gui tiles with generated art carry the item_model through the same blueprint`() {
        scenario("item-icon-parity-art") {
            val registry = sim.services.getOrThrow<IItemRegistry>()
            val blueprints = sim.services.getOrThrow<ItemBlueprintFactory>()
            val models = GameSim.defaultContentDir().resolve("models/generated/items")

            val guiItems = registry.all().filter { it.id.value.startsWith("gui-") }
            assertThat(guiItems).`as`("gui-* items are loaded").hasSizeGreaterThan(40)
            val pending = mutableListOf<String>()
            for (spec in guiItems) {
                val id = spec.id.value
                val hasArt = Files.isRegularFile(models.resolve("$id.model.json"))
                val bp = blueprints.build(spec, null)
                if (hasArt) {
                    assertThat(bp.itemModel).`as`("item_model of $id").isEqualTo("astera:$id")
                } else {
                    assertThat(bp.itemModel).`as`("no art, no item_model for $id").isNull()
                    if (id !in vanillaLook) pending += id
                }
            }
            // Tiles that should have art but have no generated icon yet (tools/item-art has no design for them).
            assertThat(pending).containsExactlyInAnyOrder("gui-tile-admin", "gui-tile-item-catalog")
        }
    }
}
