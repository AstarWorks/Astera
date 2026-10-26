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

    /** Slot of the offer whose lore contains [needle] (offers are found by what they say, never by a fixed slot). */
    private fun slotWithLore(b: Bot, s: ScreenView, needle: String): Int {
        val items = b.contents[s.containerId].orEmpty()
        return checkNotNull(items.indexOfFirst { it.amount > 0 && it.lore.contains(needle) }.takeIf { it >= 0 }) {
            "no shop slot with lore containing '$needle'; lores=${items.map { it.lore.take(60) }}"
        }
    }

    private fun gold(b: Bot): Long =
        Regex("GOLD:\\s*(\\d+)").find(plain(listOf(rcon("astera economy balance gold ${b.name}"))).first())?.groupValues?.get(1)?.toLong() ?: -1L

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
            val staffSlot = slotWithLore(b, s, "Recommended first buy")
            assertThat(staffSlot).describedAs("the recommended offer is pinned first").isEqualTo(0)
            assertThat(items[staffSlot].lore).describedAs("recommended lore shows the price").contains("Price: 150 GOLD")
            "title='${s.title}' recommended slot=$staffSlot lore=${items[staffSlot].lore}"
        }
        val s = checkNotNull(shop)
        val staffSlot = slotWithLore(b, s, "Recommended first buy")

        E2eReport.check(suite, "first click asks for confirmation and charges nothing", "SystemChat + RCON balance") {
            val chatFrom = b.chat.size
            click(b, s, staffSlot)
            val lines = plain(b.chatSince(chatFrom))
            assertThat(lines.any { it.contains("click the item again") }).describedAs("no confirm prompt; chat=$lines").isTrue()
            assertThat(gold(b)).describedAs("GOLD changed on the first click").isEqualTo(150L)
            "chat=$lines"
        }

        E2eReport.check(suite, "second click pays 150 GOLD and the staff arrives in the real inventory", "SystemChat + RCON balance + inventory") {
            val chatFrom = b.chat.size
            Thread.sleep(600)
            click(b, s, staffSlot)
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
            val other = slotWithLore(b, s, "Price: 250 GOLD") // a gun nobody carries, wallet is empty
            click(b, s, other)
            Thread.sleep(600)
            click(b, s, other)
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
        var draughtSlot = -1

        E2eReport.check(suite, "buying the healing draught (40 GOLD, no confirm below 100) drops GOLD to 60 and delivers it", "SystemChat + RCON balance + inventory") {
            draughtSlot = slotWithLore(b, s, "restore 8 HP")
            assertThat(b.contents[s.containerId]!![draughtSlot].lore).describedAs("draught offer price").contains("Price: 40 GOLD")
            val chatFrom = b.chat.size
            click(b, s, draughtSlot)
            b.waitUntil(3_000) { hotbarSlotOf(b, "glistering_melon_slice") != null }
            val lines = plain(b.chatSince(chatFrom))
            assertThat(gold(b)).describedAs("GOLD after paying 40; chat=$lines").isEqualTo(60L)
            assertThat(hotbarSlotOf(b, "glistering_melon_slice")).describedAs("healing draught missing from the hotbar").isNotNull()
            "chat=$lines"
        }

        E2eReport.check(suite, "the same offer can be bought again (unlimited) and charges again", "RCON balance") {
            Thread.sleep(600)
            click(b, s, draughtSlot)
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

    @Test
    fun `the shop leads to the cosmetics menu where a cheap colour is bought with one click and worn`() {
        val b = bot("Dresser")
        Thread.sleep(2_500)
        rcon("astera economy grant ${b.name} GOLD 60")
        Thread.sleep(500)
        val shop = openShop(b)

        var menu: ScreenView? = null
        E2eReport.check(suite, "the shop footer has a Cosmetics button that opens the cosmetics menu listing the 60 GOLD colour", "SetContainerContent + OpenScreen") {
            val items = b.contents[shop.containerId].orEmpty()
            val button = items.indexOfFirst { it.amount > 0 && it.lore.contains("Name colours, titles and kill effects") }
            assertThat(button).describedAs("no cosmetics button in the shop footer").isGreaterThanOrEqualTo(0)
            val before = b.screens.size
            click(b, shop, button)
            check(b.waitUntil(5_000) { b.screens.size > before }) { "cosmetics button opened nothing; chat=${b.chatTexts().takeLast(3)}" }
            val m = b.screens.last()
            menu = m
            b.waitUntil(3_000) { b.contents[m.containerId]?.any { it.lore.contains("60 GOLD") } == true }
            assertThat(rawKeys(listOf(m.title))).describedAs("raw key as title").isEmpty()
            assertThat(b.contents[m.containerId].orEmpty().any { it.lore.contains("Name colour") && it.lore.contains("60 GOLD") })
                .describedAs("no 60 GOLD name colour in the menu").isTrue()
            "title='${m.title}' button slot=$button"
        }
        val m = checkNotNull(menu)

        E2eReport.check(suite, "one click buys the colour (no confirm under 100 GOLD), charges 60 and the menu shows it equipped", "SystemChat + RCON balance + SetContainerContent") {
            val slot = b.contents[m.containerId].orEmpty().indexOfFirst { it.lore.contains("Name colour") && it.lore.contains("60 GOLD") }
            val goldBefore = gold(b)
            assertThat(goldBefore).describedAs("wallet must cover the 60 GOLD colour").isGreaterThanOrEqualTo(60L)
            val chatFrom = b.chat.size
            click(b, m, slot)
            b.waitUntil(3_000) { gold(b) == goldBefore - 60L }
            val lines = plain(b.chatSince(chatFrom))
            assertThat(lines.any { it.contains("Bought") }).describedAs("no purchase message; chat=$lines").isTrue()
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            assertThat(gold(b)).describedAs("GOLD after the 60 GOLD colour").isEqualTo(goldBefore - 60L)
            val equipped = { b.contents[b.screens.last().containerId].orEmpty().any { it.lore.contains("Name colour") && it.lore.contains("Equipped") } }
            val ok = b.waitUntil(3_000) { equipped() }
            assertThat(ok).describedAs("no name colour entry shows Equipped; lores=${b.contents[b.screens.last().containerId].orEmpty().map { it.lore.take(60) }}").isTrue()
            "chat=$lines"
        }
    }
}
