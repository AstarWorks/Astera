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

    private companion object {
        /** content/balance/starter-grant.yaml wallet_grants.GOLD (ECO-11). */
        const val STARTER_GOLD = 150L
    }

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

    /**
     * A first-time player holds the starter GOLD (STARTER_GOLD, content/balance/starter-grant.yaml) at once; the daily login bonus
     * (10 GOLD, ClaimLoginBonusOnJoinListener) lands ~5 s after the join. Wait for both, then grant [amount] and return the wallet
     * the player held before the grant, so the checks do not race the bonus.
     */
    private fun fund(b: Bot, amount: Long): Long {
        check(b.waitUntil(15_000) { gold(b) >= STARTER_GOLD + 10L }) { "the starter GOLD and login bonus never reached ${b.name}'s wallet (gold=${gold(b)})" }
        val base = gold(b)
        rcon("astera economy grant ${b.name} GOLD $amount")
        b.waitUntil(3_000) { gold(b) == base + amount }
        return base
    }

    private fun hasStaff(b: Bot): Boolean =
        listOf("container.*", "hotbar.*").any { rcon("execute if items entity ${b.name} $it minecraft:blaze_rod").contains("passed") }

    @Test
    fun `a brand-new player buys the recommended first offer with the starter GOLD alone`() {
        val b = bot("Newbie")
        E2eReport.check(suite, "ECO-11: a fresh player holds the starter GOLD, enough for the 150 GOLD recommended staff, without any grant", "RCON balance") {
            assertThat(b.waitUntil(15_000) { gold(b) >= STARTER_GOLD }).describedAs("starter GOLD missing, gold=${gold(b)}").isTrue()
            "gold=${gold(b)}"
        }
        val s = openShop(b)
        val staffSlot = slotWithLore(b, s, "Recommended first buy")
        // the login bonus may land meanwhile: only the delta of the purchase is asserted
        val before = gold(b)
        E2eReport.check(suite, "ECO-11: two clicks on the recommended staff pay 150 GOLD from the starter money and deliver it", "SystemChat + RCON balance + inventory") {
            val chatFrom = b.chat.size
            click(b, s, staffSlot)
            Thread.sleep(600)
            click(b, s, staffSlot)
            val lines = plain(b.chatSince(chatFrom))
            assertThat(lines.any { it.contains("Purchased") }).describedAs("no purchase confirmation; chat=$lines").isTrue()
            b.waitUntil(3_000) { hasStaff(b) }
            assertThat(hasStaff(b)).describedAs("no blaze rod (frost-staff) in the inventory").isTrue()
            assertThat(gold(b)).describedAs("GOLD after paying 150").isGreaterThanOrEqualTo(before - 150L).isLessThan(before)
            "chat=$lines"
        }
    }

    @Test
    fun `buying a shop weapon asks to confirm, charges the price and delivers the item`() {
        val b = bot("Shopper")
        val base = fund(b, 150L)

        var shop: ScreenView? = null
        E2eReport.check(suite, "a newcomer sees the recommended staff first (150 GOLD), no raw keys", "ClientboundOpenScreen + SetContainerContent") {
            val s = openShop(b)
            shop = s
            val items = b.contents[s.containerId].orEmpty()
            assertThat(rawKeys(listOf(s.title))).describedAs("raw key as title").isEmpty()
            val staffSlot = slotWithLore(b, s, "Recommended first buy")
            assertThat(staffSlot).describedAs("the recommended offer is pinned first").isEqualTo(0)
            assertThat(items[staffSlot].lore).describedAs("recommended lore shows the price").contains("Price: 150 GOLD")
            val rawTriggers = Regex("(left_click|right_click|sneak_right|sneak_left|sneak_drop|drop):")
            assertThat(items.filter { it.amount > 0 && rawTriggers.containsMatchIn(it.lore) }.map { it.lore.take(80) })
                .describedAs("weapon lore shows labelled inputs (Left-click: ...), never raw trigger ids").isEmpty()
            "title='${s.title}' recommended slot=$staffSlot lore=${items[staffSlot].lore}"
        }
        val s = checkNotNull(shop)
        val staffSlot = slotWithLore(b, s, "Recommended first buy")

        E2eReport.check(suite, "first click asks for confirmation and charges nothing", "SystemChat + RCON balance") {
            val chatFrom = b.chat.size
            click(b, s, staffSlot)
            val lines = plain(b.chatSince(chatFrom))
            assertThat(lines.any { it.contains("click the item again") }).describedAs("no confirm prompt; chat=$lines").isTrue()
            assertThat(gold(b)).describedAs("GOLD changed on the first click").isEqualTo(base + 150L)
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
            assertThat(gold(b)).describedAs("GOLD after paying 150").isEqualTo(base)
            assertThat(hasStaff(b)).describedAs("no blaze rod (frost-staff) in the server inventory").isTrue()
            "chat=$lines"
        }

        E2eReport.check(suite, "with no spare GOLD another offer is refused with the shortfall and nothing is charged", "SystemChat + RCON balance") {
            val chatFrom = b.chat.size
            Thread.sleep(600)
            val other = slotWithLore(b, s, "Price: 250 GOLD") // a gun nobody carries, the wallet holds only the login bonus
            click(b, s, other)
            Thread.sleep(600)
            click(b, s, other)
            val lines = plain(b.chatSince(chatFrom))
            assertThat(lines.any { it.contains("Not enough GOLD") }).describedAs("no shortfall message; chat=$lines").isTrue()
            assertThat(gold(b)).describedAs("balance must stay unchanged").isEqualTo(base)
            "chat=$lines"
        }
    }

    private fun hotbarSlotOf(b: Bot, material: String): Int? =
        (0..8).firstOrNull { rcon("execute if items entity ${b.name} hotbar.$it minecraft:$material").contains("passed") }

    @Test
    fun `a consumable costs GOLD on every purchase, arrives in the inventory and is used up by right-click`() {
        val b = bot("Drinker")
        val base = fund(b, 100L)
        val s = openShop(b)
        var draughtSlot = -1

        E2eReport.check(suite, "buying the healing draught (40 GOLD, no confirm below 100) drops GOLD to 60 and delivers it", "SystemChat + RCON balance + inventory") {
            draughtSlot = slotWithLore(b, s, "restore 40 HP")
            assertThat(b.contents[s.containerId]!![draughtSlot].lore).describedAs("draught offer price").contains("Price: 40 GOLD")
            val chatFrom = b.chat.size
            click(b, s, draughtSlot)
            b.waitUntil(3_000) { hotbarSlotOf(b, "glistering_melon_slice") != null }
            val lines = plain(b.chatSince(chatFrom))
            assertThat(gold(b)).describedAs("GOLD after paying 40; chat=$lines").isEqualTo(base + 60L)
            assertThat(hotbarSlotOf(b, "glistering_melon_slice")).describedAs("healing draught missing from the hotbar").isNotNull()
            "chat=$lines"
        }

        E2eReport.check(suite, "the same offer can be bought again (unlimited) and charges again", "RCON balance") {
            Thread.sleep(600)
            click(b, s, draughtSlot)
            b.waitUntil(3_000) { gold(b) == base + 20L }
            assertThat(gold(b)).describedAs("second purchase must charge again").isEqualTo(base + 20L)
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
        fund(b, 60L)
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
