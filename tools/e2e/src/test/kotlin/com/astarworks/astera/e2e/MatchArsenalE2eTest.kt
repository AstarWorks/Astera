package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.junit.jupiter.api.Test

/**
 * match-arsenal on a real server: a carry-tnt (siege-isles-tnt) match with two bots. Covers the behaviour rows of the track's understanding
 * file: base shop NPCs (own team opens the ore shop, the enemy is refused), buying with ore (weapon + armor that auto-equips), the base
 * ore generator, the free stone tool loan, Mid ore nodes (tier gate, drops, regrow), a pickaxe tool skill (break_area) and hand-mining speed.
 *
 * Stage-local coordinates (the compact debug-siege-isles copy the e2e image bakes): shop NPCs at (-70.5, 65, 7.5) red / (70.5, 65, 7.5) blue,
 * Mid iron node at (5, 65, 5) (STONE), gold node at (0, 65, 12) (IRON), red iron generator at (-81.5, 65, -6).
 */
class MatchArsenalE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Match arsenal"

    private fun count(b: Bot, material: String): Int =
        Regex("(\\d+) matching").find(clean(rcon("clear ${b.name} minecraft:$material 0")))?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun hasItems(b: Bot, slot: String, material: String): Boolean =
        rcon("execute if items entity ${b.name} $slot minecraft:$material").contains("passed")

    private fun hotbarSlotOf(b: Bot, material: String): Int? =
        (0..8).firstOrNull { hasItems(b, "hotbar.$it", material) }

    private fun selectTool(b: Bot, material: String): Int? {
        val slot = hotbarSlotOf(b, material) ?: return null
        b.selectHotbar((slot + 1) % 9)
        Thread.sleep(150)
        b.selectHotbar(slot)
        Thread.sleep(300)
        return slot
    }

    /** Entity id the bot saw for a villager within 3 blocks of the absolute (x, z). */
    private fun villagerNear(b: Bot, x: Double, z: Double): Int? =
        b.entities.values.firstOrNull { it.type == "VILLAGER" && Math.hypot(it.x - x, it.z - z) < 3.0 }?.id

    private fun openByInteract(b: Bot, npcId: Int): ScreenView? {
        val before = b.screens.size
        b.interact(npcId)
        if (!b.waitUntil(5_000) { b.screens.size > before }) return null
        val s = b.screens.last()
        b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
        return s
    }

    private fun click(b: Bot, s: ScreenView, slot: Int) =
        b.clickSlot(s.containerId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)

    @Test
    fun `the arsenal works end to end in a siege-isles match`() {
        val ctx = startMatch(suite, "carry-tnt", "Ar") ?: return
        val a = ctx.actor
        val keep = listOf(ctx.idle)
        Thread.sleep(2_000)

        // --- 4. free tool loan -----------------------------------------------------------------------------------------------
        E2eReport.check(suite, "the bot spawns with a stone pickaxe, axe and shovel", "RCON clear (count)") {
            val have = listOf("stone_pickaxe", "stone_axe", "stone_shovel").associateWith { count(a, it) }
            assertThat(have.values).describedAs("tool loan: $have").allMatch { it >= 1 }
            "loan=$have"
        }

        // --- 1. shop NPCs --------------------------------------------------------------------------------------------------
        val redX = -70.5 + originX
        val redZ = 7.5 + originZ
        val blueX = 70.5 + originX
        inArena("run kill @e[type=minecraft:item]")
        tpArena(a.name, -68.5, 65.0, 7.5, 90f, 0f)
        Thread.sleep(2_500)
        var redNpc: Int? = null
        E2eReport.check(suite, "a shop NPC (villager) stands at the red base and is visible to the red bot", "RCON entity query + ClientboundAddEntity") {
            val server = inArena("if entity @e[type=minecraft:villager,x=$redX,y=65,z=$redZ,distance=..3]")
            assertThat(server).describedAs("no villager at the red shop spot ($redX, 65, $redZ)").contains("passed")
            redNpc = villagerNear(a, redX, redZ)
            assertThat(redNpc).describedAs("client saw no villager near the red NPC; villagers=${a.entities.values.filter { it.type == "VILLAGER" }.map { Triple(it.id, it.x, it.z) }}").isNotNull()
            "server confirms villager at red base; client entity id=$redNpc"
        }
        E2eReport.check(suite, "a shop NPC stands at the blue base", "RCON entity query") {
            val server = inArena("if entity @e[type=minecraft:villager,x=$blueX,y=65,z=$redZ,distance=..3]")
            assertThat(server).describedAs("no villager at the blue shop spot ($blueX, 65, $redZ)").contains("passed")
            "villager at blue base"
        }
        val npc = redNpc ?: return

        // --- 2. ore + shop ------------------------------------------------------------------------------------------------
        rcon("astera items give ${a.name} ore-iron 20")
        a.waitUntil(5_000) { count(a, "iron_ingot") >= 20 }
        val ironStart = count(a, "iron_ingot")
        var shop: ScreenView? = null
        E2eReport.check(suite, "right-clicking the own team's shop NPC opens the ore shop with ore prices", "OpenScreen + SetContainerContent") {
            Thread.sleep(1_100)
            val s = openByInteract(a, npc)
            assertThat(s).describedAs("own NPC opened nothing; chat=${a.chatTexts().takeLast(4)}").isNotNull()
            shop = s
            val items = a.contents[s!!.containerId].orEmpty()
            assertThat(rawKeys(listOf(s.title))).describedAs("raw key as title").isEmpty()
            assertThat(items.any { it.amount > 0 && it.lore.contains("Iron x6") }).describedAs("no 'Iron x6' price lore (dagger): ${items.map { it.lore.take(50) }}").isTrue()
            "title='${s.title}' iron=$ironStart lore0=${items.firstOrNull()?.lore}"
        }
        val s = shop ?: return
        E2eReport.check(suite, "buying the dagger removes 6 iron ore and delivers the item", "SystemChat + RCON clear") {
            val slot = a.contents[s.containerId].orEmpty().indexOfFirst { it.amount > 0 && it.lore.contains("Iron x6") }
            val chatFrom = a.chat.size
            click(a, s, slot)
            a.waitUntil(4_000) { count(a, "iron_ingot") == ironStart - 6 }
            val lines = a.chat.drop(chatFrom).map { clean(it.text) }
            assertThat(count(a, "iron_ingot")).describedAs("iron after dagger; chat=$lines").isEqualTo(ironStart - 6)
            assertThat(count(a, "stone_sword")).describedAs("dagger (stone_sword) not in inventory; chat=$lines").isGreaterThanOrEqualTo(1)
            assertThat(lines.any { it.contains("Bought") }).describedAs("no purchase message; chat=$lines").isTrue()
            "iron $ironStart -> ${count(a, "iron_ingot")}; chat=$lines"
        }
        E2eReport.check(suite, "buying a leather helmet in the armor tab removes 4 iron and equips it", "RCON clear + armor.head") {
            Thread.sleep(700)
            val tabSlot = a.contents[s.containerId].orEmpty().withIndex().lastOrNull { it.value.amount > 0 && it.value.lore.contains("open this tab") }?.index
            assertThat(tabSlot).describedAs("no tab buttons in the shop").isNotNull()
            click(a, s, tabSlot!!)
            a.waitUntil(4_000) { a.contents[s.containerId].orEmpty().any { it.amount > 0 && it.lore.contains("Iron x4") } }
            val slot = a.contents[s.containerId].orEmpty().indexOfFirst { it.amount > 0 && it.lore.contains("Iron x4") }
            assertThat(slot).describedAs("armor tab has no 'Iron x4' offer: ${a.contents[s.containerId].orEmpty().map { it.lore.take(40) }}").isGreaterThanOrEqualTo(0)
            val before = count(a, "iron_ingot")
            Thread.sleep(700)
            click(a, s, slot)
            a.waitUntil(4_000) { count(a, "iron_ingot") == before - 4 }
            val worn = a.waitUntil(3_000) { hasItems(a, "armor.head", "leather_helmet") }
            assertThat(count(a, "iron_ingot")).describedAs("iron after helmet").isEqualTo(before - 4)
            assertThat(worn).describedAs("helmet not equipped in armor.head; leather_helmet count=${count(a, "leather_helmet")}").isTrue()
            "iron $before -> ${count(a, "iron_ingot")}; armor.head=leather_helmet"
        }
        a.closeWindow(s.containerId)

        // --- 1b. enemy NPC refuses ---------------------------------------------------------------------------------------
        E2eReport.check(suite, "right-clicking the enemy team's NPC opens nothing and says it is not your shop", "SystemChat") {
            tpArena(a.name, 68.5, 65.0, 7.5, 270f, 0f)
            Thread.sleep(2_500)
            val blueNpc = villagerNear(a, blueX, redZ)
            assertThat(blueNpc).describedAs("client saw no blue NPC").isNotNull()
            val screens = a.screens.size
            val chatFrom = a.chat.size
            a.interact(blueNpc!!)
            a.waitUntil(4_000) { a.chat.size > chatFrom }
            val lines = a.chat.drop(chatFrom).map { clean(it.text) }
            assertThat(a.screens.size).describedAs("enemy NPC opened a screen").isEqualTo(screens)
            assertThat(lines.any { it.contains("shop") && it.contains("team") }).describedAs("no wrong-team message; chat=$lines").isTrue()
            assertThat(rawKeys(lines)).describedAs("raw keys").isEmpty()
            "chat=$lines"
        }

        // --- 3. base generator --------------------------------------------------------------------------------------------
        E2eReport.check(suite, "the red base iron generator drops ore items on the ground within its interval", "RCON entity query") {
            tpArena(a.name, -60.0, 65.0, 20.0, 0f, 0f)
            inArena("run kill @e[type=minecraft:item]")
            val gx = -81.5 + originX
            val gz = -6.0 + originZ
            var found = false
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end && !found) {
                keep.forEach { it.nudge() }
                Thread.sleep(1_000)
                found = inArena("if entity @e[type=minecraft:item,x=$gx,y=65,z=$gz,distance=..5]").contains("passed")
            }
            assertThat(found).describedAs("no item entity within 5 blocks of the generator ($gx, 65, $gz) after 20s").isTrue()
            val kind = clean(inArena("run data get entity @e[type=minecraft:item,x=$gx,y=65,z=$gz,distance=..5,limit=1] Item.id"))
            "item entity near generator: $kind"
        }

        // --- 5. Mid ore nodes ---------------------------------------------------------------------------------------------
        val ironBefore = count(a, "iron_ingot")
        E2eReport.check(suite, "the Mid iron ore node exists at match start", "RCON execute if block") {
            assertThat(arenaBlockIs(5, 65, 5, "minecraft:iron_ore")).describedAs("no iron_ore at stage-local (5,65,5)").isTrue()
            "iron_ore at (5,65,5)"
        }
        E2eReport.check(suite, "mining the gold node with a stone pickaxe is refused with a hint and the block stays", "ClientboundSetActionBar + block") {
            selectTool(a, "stone_pickaxe")
            tpArena(a.name, 0.5, 65.0, 10.5, 0f, 0f)
            Thread.sleep(1_500)
            val barsFrom = a.actionBars.size
            val goldBefore = count(a, "gold_ingot")
            a.dig(0 + originX, 65, 12 + originZ)
            Thread.sleep(800)
            val bars = a.actionBars.drop(barsFrom).map { clean(it.text) }
            assertThat(bars.any { it.contains("pickaxe") }).describedAs("no tier hint on the action bar; bars=$bars").isTrue()
            assertThat(count(a, "gold_ingot")).describedAs("gold ore was given anyway").isEqualTo(goldBefore)
            assertThat(arenaBlockIs(0, 65, 12, "minecraft:gold_ore")).describedAs("gold node was removed").isTrue()
            "bars=$bars"
        }
        E2eReport.check(suite, "mining the iron node with a stone pickaxe gives 2 iron ore and swaps in a placeholder", "RCON clear + block") {
            selectTool(a, "stone_pickaxe")
            tpArena(a.name, 5.5, 65.0, 3.5, 0f, 0f)
            Thread.sleep(1_500)
            a.dig(5 + originX, 65, 5 + originZ)
            val got = a.waitUntil(4_000) { count(a, "iron_ingot") > ironBefore }
            Thread.sleep(500)
            assertThat(got).describedAs("no ore item arrived; iron=${count(a, "iron_ingot")} (before $ironBefore); chat=${a.chatTexts().takeLast(3)}").isTrue()
            assertThat(count(a, "iron_ingot") - ironBefore).describedAs("drop count").isEqualTo(2)
            assertThat(arenaBlockIs(5, 65, 5, "minecraft:iron_ore")).describedAs("node did not change to a placeholder").isFalse()
            "iron +${count(a, "iron_ingot") - ironBefore}; block now ${clean(inArena("run data get block ${5 + originX} 65 ${5 + originZ} id"))}"
        }
        E2eReport.check(suite, "the iron node regrows after its regrow time (20 s)", "RCON execute if block") {
            var back = false
            val end = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < end && !back) {
                keep.forEach { it.nudge() }
                a.nudge()
                Thread.sleep(1_000)
                back = arenaBlockIs(5, 65, 5, "minecraft:iron_ore")
            }
            assertThat(back).describedAs("iron_ore did not return within 45s").isTrue()
            "node back"
        }

        // --- 6. tool skill break_area ---------------------------------------------------------------------------------------
        E2eReport.check(suite, "a stone pickaxe right-click (bore) removes the blocks in front inside the arena", "RCON execute if block") {
            // A 3x3 glass wall at x=-60, the bot stands at x=-63.5 facing east (yaw 270), bore reaches 4 blocks.
            for (y in 65..67) for (z in 4..6) inArena("run setblock ${-60 + originX} $y ${z + originZ} minecraft:glass")
            tpArena(a.name, -63.5, 65.0, 5.5, 270f, 0f)
            Thread.sleep(1_500)
            val slot = selectTool(a, "stone_pickaxe")
            assertThat(slot).describedAs("no stone pickaxe in the hotbar").isNotNull()
            fun solid() = (65..67).flatMap { y -> (4..6).map { z -> y to z } }.count { (y, z) -> arenaSolid(-60, y, z) }
            val before = solid()
            a.useItem(270f, 0f)
            Thread.sleep(1_500)
            // Glass is cover (tempered-glass, 250 HP): one stone-pickaxe bore chips it, a second one (after the 8 s cooldown) breaks it.
            if (solid() == before) {
                Thread.sleep(7_000)
                a.useItem(270f, 0f)
                Thread.sleep(1_500)
            }
            val after = solid()
            assertThat(before).describedAs("test wall was not placed").isEqualTo(9)
            assertThat(after).describedAs("bore removed nothing from the 3x3 glass wall; chat=${a.chatTexts().takeLast(3)}; bars=${a.actionBars.takeLast(2).map { it.text }}").isLessThan(before)
            "wall solid cells $before -> $after"
        }

        // --- 7. hand-mining a custom block ----------------------------------------------------------------------------------
        // astera:reinforced_planks is DARK_OAK_PLANKS cover (450 HP) and the axe is its tool. A left-click is one swing
        // (HitCustomBlockUseCase), so the number of swings until it is gone is the speed.
        fun swingsToBreak(tool: String?): Int? {
            if (tool != null) selectTool(a, tool) else {
                val empty = (0..8).firstOrNull { rcon("execute if items entity ${a.name} hotbar.$it *").contains("failed") } ?: return null
                a.selectHotbar(empty)
                Thread.sleep(300)
            }
            inArena("run setblock ${-60 + originX} 66 ${0 + originZ} minecraft:dark_oak_planks")
            tpArena(a.name, -62.5, 65.0, 0.5, 270f, 0f)
            Thread.sleep(500)
            for (n in 1..60) {
                a.dig(-60 + originX, 66, 0 + originZ, 100L)
                Thread.sleep(400)
                if (!arenaBlockIs(-60, 66, 0, "minecraft:dark_oak_planks")) return n
            }
            return null
        }
        E2eReport.check(suite, "hand-mining reinforced planks with an axe is clearly faster than with a bare hand", "left-click swings until the block is gone (RCON block)") {
            val withAxe = swingsToBreak("stone_axe")
            val bare = swingsToBreak(null)
            assertThat(withAxe).describedAs("the axe never broke the planks within 60 swings").isNotNull()
            assertThat(bare ?: Int.MAX_VALUE).describedAs("bare hand $bare swings vs axe $withAxe swings").isGreaterThanOrEqualTo(withAxe!! * 2)
            "axe=$withAxe swings bare=${bare ?: ">60"} swings"
        }

        E2eReport.check(suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(ctx.logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
