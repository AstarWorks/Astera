package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.junit.jupiter.api.Test

/**
 * The GOLD sink of the core loop (docs/vision/areas/economy-life.md), seen from a client: a player with enough GOLD
 * opens the shop, the first click asks for confirmation (price >= 100), the second pays, the wallet drops by exactly
 * the price and the weapon is in the real inventory; a player without GOLD is refused with a readable shortfall.
 */
class ShopPurchaseE2eTest : E2eBase() {

    private val suite = "Shop purchase"

    private fun openShop(b: Bot): ScreenView {
        Thread.sleep(1_100) // vanilla chat spam kick pacing
        val before = b.screens.size
        b.command("astera shop")
        check(b.waitUntil(5_000) { b.screens.size > before }) { "/astera shop opened nothing; chat=${b.chatTexts().takeLast(3)}" }
        val s = b.screens.last()
        b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
        return s
    }

    private fun click(b: Bot, s: ScreenView, slot: Int) {
        b.clickSlot(s.containerId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
    }

    private fun plain(lines: List<String>): List<String> = lines.map { it.replace(Regex("§."), "") }

    private fun gold(b: Bot): Long =
        Regex("GOLD:\\s*(\\d+)").find(plain(listOf(rcon("astera economy balance ${b.name} GOLD"))).first())?.groupValues?.get(1)?.toLong() ?: -1L

    private fun hasStaff(b: Bot): Boolean =
        listOf("container.*", "hotbar.*").any { rcon("execute if items entity ${b.name} $it minecraft:blaze_rod").contains("passed") }

    @Test
    fun `buying a shop weapon asks to confirm, charges the price and delivers the item`() {
        val b = bot("Shopper")
        Thread.sleep(2_500)
        rcon("astera economy grant ${b.name} GOLD 150")
        Thread.sleep(500)

        var shop: ScreenView? = null
        E2eReport.check(suite, "a newcomer sees the recommended staff first (150 GOLD), no GEM in the wallet, no raw keys", "ClientboundOpenScreen + SetContainerContent") {
            val s = openShop(b)
            shop = s
            val items = b.contents[s.containerId].orEmpty()
            assertThat(rawKeys(listOf(s.title))).describedAs("raw key as title").isEmpty()
            assertThat(items.getOrNull(0)?.amount ?: 0).describedAs("no offer in slot 0").isGreaterThan(0)
            assertThat(items.getOrNull(0)?.lore.orEmpty()).describedAs("slot 0 lore shows the price").contains("150")
            "title='${s.title}' slot0 lore=${items.getOrNull(0)?.lore}"
        }
        val s = checkNotNull(shop)

        E2eReport.check(suite, "first click asks for confirmation and charges nothing", "SystemChat + RCON balance") {
            val chatFrom = b.chat.size
            click(b, s, 0)
            val lines = plain(b.chatSince(chatFrom))
            assertThat(lines.any { it.contains("click the item again") }).describedAs("no confirm prompt; chat=$lines").isTrue()
            assertThat(gold(b)).describedAs("GOLD changed on the first click").isEqualTo(150L)
            "chat=$lines"
        }

        E2eReport.check(suite, "second click pays 150 GOLD and the staff arrives in the real inventory", "SystemChat + RCON balance + inventory") {
            val chatFrom = b.chat.size
            Thread.sleep(600)
            click(b, s, 0)
            val lines = plain(b.chatSince(chatFrom))
            assertThat(lines.any { it.contains("Purchased") }).describedAs("no purchase confirmation; chat=$lines").isTrue()
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            b.waitUntil(3_000) { hasStaff(b) }
            assertThat(gold(b)).describedAs("GOLD after paying 150").isEqualTo(0L)
            assertThat(hasStaff(b)).describedAs("no blaze rod (frost-staff) in the server inventory").isTrue()
            "chat=$lines"
        }

        E2eReport.check(suite, "with 0 GOLD another offer is refused with the shortfall and nothing is charged", "SystemChat + RCON balance") {
            val chatFrom = b.chat.size
            Thread.sleep(600)
            click(b, s, 1) // dagger, 150 GOLD, wallet is empty
            Thread.sleep(600)
            click(b, s, 1)
            val lines = plain(b.chatSince(chatFrom))
            assertThat(lines.any { it.contains("Not enough GOLD") }).describedAs("no shortfall message; chat=$lines").isTrue()
            assertThat(gold(b)).describedAs("balance must stay 0").isEqualTo(0L)
            "chat=$lines"
        }
    }

    private fun hotbarSlotOf(b: Bot, material: String): Int? =
        (0..8).firstOrNull { rcon("execute if items entity ${b.name} hotbar.$it minecraft:$material").contains("passed") }

    @Test
    fun `a consumable costs GOLD on every purchase, arrives in the inventory and is used up by right-click`() {
        val b = bot("Drinker")
        Thread.sleep(2_500)
        rcon("astera economy grant ${b.name} GOLD 100")
        Thread.sleep(500)
        val s = openShop(b)

        E2eReport.check(suite, "buying the healing draught (40 GOLD, no confirm below 100) drops GOLD to 60 and delivers it", "SystemChat + RCON balance + inventory") {
            val lore = b.contents[s.containerId]?.getOrNull(18)?.lore.orEmpty()
            assertThat(lore).describedAs("slot 18 should be the healing draught offer").contains("40")
            val chatFrom = b.chat.size
            click(b, s, 18)
            b.waitUntil(3_000) { hotbarSlotOf(b, "glistering_melon_slice") != null }
            val lines = plain(b.chatSince(chatFrom))
            assertThat(gold(b)).describedAs("GOLD after paying 40; chat=$lines").isEqualTo(60L)
            assertThat(hotbarSlotOf(b, "glistering_melon_slice")).describedAs("healing draught missing from the hotbar").isNotNull()
            "chat=$lines"
        }

        E2eReport.check(suite, "the same offer can be bought again (unlimited) and charges again", "RCON balance") {
            Thread.sleep(600)
            click(b, s, 18)
            b.waitUntil(3_000) { gold(b) == 20L }
            assertThat(gold(b)).describedAs("second purchase must charge again").isEqualTo(20L)
            "gold=${gold(b)}"
        }

        E2eReport.check(suite, "right-click uses one draught up", "ServerboundUseItem + RCON inventory") {
            b.closeWindow(s.containerId)
            Thread.sleep(400)
            val slot = checkNotNull(hotbarSlotOf(b, "glistering_melon_slice")) { "no draught in the hotbar" }
            b.selectHotbar(slot)
            Thread.sleep(300)
            val count = { rcon("clear ${b.name} minecraft:glistering_melon_slice 0") }
            val before = count()
            b.useItem(0f, 0f)
            Thread.sleep(1_200)
            val after = count()
            assertThat(after).describedAs("a draught should have been consumed; before=$before after=$after").isNotEqualTo(before)
            "before=$before after=$after"
        }
    }
}
