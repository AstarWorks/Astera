package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.EconomyPlanner
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.economy.application.service.MatchShopLoaderService
import com.astarworks.astera.economy.application.usecase.shop.MatchShopPurchaseUseCase
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Bot lull economy: the very [EconomyPlanner] and [StageObjectives] sites the live BotBrain uses drive a `Bot_` sim player through
 * the shop ladder of the shipped `content/shops/match-shop.yaml` on the real carry-tnt stage. Walking is a teleport to each planned
 * step; ground pickup of base generator drops is not simulated, so a `collect` step hands the generator ore over (the server's own
 * generator code is covered elsewhere). Mining goes through the real OreNodeService, buying through the real MatchShopBotBuyer.
 */
class BotEconomyScenarioTest {

    @Test
    fun `the shipped shop parses to a shopping list - base armour piece by piece cheapest first, upgrades, Mid armour (no tools) - and bag names map to ore ids`() {
        val list = EconomyPlanner.parseShoppingList(Files.readString(GameSim.defaultContentDir().resolve("shops/match-shop.yaml")))
        val prices = list.map { it.price.values.single() }
        assertThat(list.map { it.items.single() }.take(4)).containsExactly("leather-scout-helmet", "leather-scout-boots", "leather-scout-leggings", "leather-scout-chestplate")
        assertThat(prices).containsExactly(4, 4, 6, 8, 2, 2, 3, 4, 3, 3, 4, 6, 6, 4, 3, 2, 2, 3, 4)
        assertThat(list.first().supersededBy).`as`("a leather helmet is pointless once a better helmet is owned").contains("chain-guard-helmet", "iron-warden-helmet", "diamond-champion-helmet")
        assertThat(list.map { it.price.keys.single() }.drop(12).take(3)).containsExactly("ore-gold", "ore-diamond", "ore-emerald")
        assertThat(EconomyPlanner.oreCounts(listOf("Diamond Ore" to 2, "ダイヤ鉱石" to 1, "Stone" to 9, "エメラルド鉱石" to 3)))
            .isEqualTo(mapOf("ore-diamond" to 3, "ore-emerald" to 3))
    }

    @Test
    fun `a bot collects gold at its base, mines diamond and emerald at Mid and buys every weapon tier at its own shop`() {
        scenario("bot-economy-ladder") {
            val objectives = checkNotNull(StageObjectives.load(GameSim.defaultContentDir().toFile(), "carry-tnt")) { "stage did not load" }
            val local = checkNotNull(objectives.economySites("red")) { "no economy sites for red" }
            val ladder = objectives.economyLadder()
            assertThat(ladder).hasSize(19)
            assertThat(local.shop).isNotNull
            assertThat(local.generators.map { it.ore }).contains("ore-gold")
            assertThat(local.nodes.map { it.ore }).contains("ore-diamond", "ore-emerald")

            val bot = player("Bot_Eco", team = "red")
            val enemy = player("blue-guard", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(bot to "red", enemy to "blue"), stage = "siege-isles-tnt")
            advance(60)
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles-tnt"))!!.origin
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val bag = checkNotNull(sim.server.view(bot.id)).givenItems
            bag.removeAll { it == "knight-sword" }
            bag += "knight-sword"
            bag += "diamond-pickaxe"

            // Mid diamond / emerald are Norma-level gated (3 / 4): the bot's objective play earns the levels; here the stars and the base check do.
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            repeat(6) {
                stars.add(matchId, bot.id, 40)
                bus.publish(StarsCollected(matchId, bot.id, 1, stars.get(matchId, bot.id)))
                advance(5)
                normaCheckAtHome(matchId, bot)
            }

            val planner = EconomyPlanner(local.shifted(origin.x, origin.z), ladder)
            fun have() = bag.filter { it.startsWith("ore-") }.groupingBy { it }.eachCount()
            val visited = LinkedHashSet<String>()
            var clock = 0L
            val log = ArrayList<String>()
            var guard = 0
            while (!planner.done && "knight-sword-t4" !in bag && guard++ < 3000) {
                val step = planner.step(clock, bot.position.x, bot.position.z, have(), hasPickaxe = true)
                    ?: if (planner.done) break else error("planner stalled at rung ${planner.rung} bag=$bag visited=$visited log=${log.takeLast(12)}")
                visited += "${step.kind}:${step.ore}"
                when (step.kind) {
                    "collect" -> {
                        bot.position = Vec3(step.x, step.y + origin.y, step.z)
                        while (bag.count { it == step.ore } < 24) bag += step.ore // the generator drops reach a standing bot
                    }
                    "shop" -> bot.position = Vec3(step.x + 1.0, step.y + origin.y, step.z)
                    "mine" -> {
                        bot.position = Vec3(step.x, step.y + origin.y, step.z)
                        val cell = checkNotNull(step.strike)
                        val out = nodes.mine(bot.id, worldId, Vec3(cell.first + origin.x, cell.second + origin.y, cell.third + origin.z), ItemId("diamond-pickaxe"))
                        log += "$out"
                    }
                }
                advance(8)
                clock += 400
            }
            assertThat("knight-sword-t4" in bag).`as`("every weapon tier bought, visited %s", visited).isTrue()
            assertThat(visited).contains("collect:ore-iron", "collect:ore-gold", "mine:ore-diamond", "mine:ore-emerald", "shop:")
            assertThat(bag).contains("knight-sword-t4")
            assertThat(bag.count { it == "knight-sword" }).isZero()
        }
    }

    @Test
    fun `a bot without a pickaxe buys armour first, never a tool, and digs Mid ore once the Norma level hands it a pickaxe`() {
        scenario("bot-economy-shopping") {
            val shopsDir = GameSim.defaultContentDir().resolve("shops")
            val yaml = Files.readString(shopsDir.resolve("match-shop.yaml"))
            val buys = EconomyPlanner.parseShoppingList(yaml)
            assertThat(buys.flatMap { it.items }).`as`("tools are never on the list").noneMatch { it.endsWith("pickaxe") || it.endsWith("axe") || it.endsWith("shovel") }
            val catalog = checkNotNull(MatchShopLoaderService().parse(shopsDir).second) { "match-shop.yaml did not load" }
            val purchase = MatchShopPurchaseUseCase(
                itemRegistry = sim.services.getOrThrow<IItemRegistry>(),
                server = sim.server,
                stock = { sim.services.getOrThrow<IOwnedItemStock>() },
                delivery = { sim.services.getOrThrow<IItemDelivery>() },
                inMatch = { true },
            )
            val bot = player("Bot_Shopper", team = "red")
            val bag = checkNotNull(sim.server.view(bot.id)).givenItems
            // A base with a shop and an iron generator only: gold is reachable solely by digging a Mid node, which needs a pickaxe the bot lacks at Lv1.
            val sites = EconomyPlanner.Sites(
                shop = EconomyPlanner.Site(0.0, 0.0, 0.0),
                generators = listOf(EconomyPlanner.Site(10.0, 0.0, 10.0, "ore-iron")),
                nodes = listOf(EconomyPlanner.Site(30.5, 0.0, 30.5, "ore-gold")),
            )
            val planner = EconomyPlanner(sites, buys)
            fun have() = bag.filter { it.startsWith("ore-") }.groupingBy { it }.eachCount()
            val bought = ArrayList<String>()
            var clock = 0L
            var guard = 0
            while (bought.size < 4 && guard++ < 400) {
                val step = planner.step(clock, bot.position.x, bot.position.z, have(), hasPickaxe = false, owned = bought.toSet())
                    ?: error("planner stalled: bought=$bought bag=$bag rung=${planner.rung}")
                bot.position = Vec3(step.x, step.y, step.z)
                when (step.kind) {
                    "collect" -> while (bag.count { it == step.ore } < 24) bag += step.ore // base generator drops reach a standing bot
                    "shop" -> if (clock % 800L == 0L) { // the server-side buyer: the first listed entry the bag pays
                        val entry = buys.firstOrNull { b -> b.items.first() !in bought && b.price.all { (ore, n) -> have()[ore].let { it != null && it >= n } } }
                        entry?.items?.forEach { id ->
                            val offer = catalog.offers.first { it.itemId.value == id }
                            assertThat(purchase.execute(bot.id, offer)).`as`("buying $id").isInstanceOf(Result.Success::class.java)
                            bought += id
                        }
                    }
                }
                advance(8)
                clock += 400
            }
            assertThat(bought).`as`("the leather armour set comes first, no tool: $bought").containsExactlyInAnyOrder(
                "leather-scout-helmet", "leather-scout-chestplate", "leather-scout-leggings", "leather-scout-boots",
            )
            // Norma Lv2 hands the bot an iron pickaxe (ADR-0319): the gold node is now the next thing to work on.
            bag += "iron-pickaxe"
            clock += 400
            val next = checkNotNull(planner.step(clock, bot.position.x, bot.position.z, have(), hasPickaxe = true, owned = bought.toSet())) { "planner idle with a pickaxe" }
            assertThat(next.kind to next.ore).isEqualTo("mine" to "ore-gold")
        }
    }

    @Test
    fun `a bot at its shop replaces armour with each better tier it can pay in full, never a lower or equal tier, and never buys a tool`() {
        scenario("bot-economy-armour-tiers") {
            val objectives = checkNotNull(StageObjectives.load(GameSim.defaultContentDir().toFile(), "carry-tnt")) { "stage did not load" }
            val local = checkNotNull(objectives.economySites("red")) { "no economy sites for red" }
            val bot = player("Bot_Armour", team = "red")
            val enemy = player("blue-guard", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(bot to "red", enemy to "blue"), stage = "siege-isles-tnt")
            advance(60)
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles-tnt"))!!.origin
            val shop = checkNotNull(local.shop)
            bot.position = Vec3(shop.x + origin.x + 1.0, shop.y + origin.y, shop.z + origin.z)
            val bag = checkNotNull(sim.server.view(bot.id)).givenItems
            val slots = listOf("helmet", "chestplate", "leggings", "boots")
            fun pieces(tier: String) = slots.map { "$tier-$it" }
            fun worn(tier: String) = pieces(tier).count { it in bag }
            val delivery = sim.services.getOrThrow<IItemDelivery>() as com.astarworks.astera.gamesim.SimItemDelivery
            fun bought() = delivery.delivered.map { it.id.value }.filter { id -> slots.any { id.endsWith("-$it") } }

            repeat(22) { bag += "ore-iron" }
            advance(60)
            assertThat(worn("leather-scout")).`as`("leather set bought with 22 iron, bag=%s", bag).isEqualTo(4)

            repeat(16) { bag += "ore-gold" }
            advance(60)
            assertThat(worn("iron-warden")).`as`("iron-warden set (gold 16) replaces leather, bag=%s", bag).isEqualTo(4)
            assertThat(worn("leather-scout")).`as`("the replaced leather is handed in").isZero()
            assertThat(worn("chain-guard")).`as`("the lower chain tier is never bought on top").isZero()

            repeat(11) { bag += "ore-diamond" }
            advance(60)
            assertThat(worn("diamond-champion")).`as`("diamond set replaces iron-warden, bag=%s", bag).isEqualTo(4)
            assertThat(worn("iron-warden")).isZero()

            repeat(40) { bag += "ore-iron"; bag += "ore-gold"; bag += "ore-diamond" }
            advance(120)
            val all = bought()
            assertThat(all).`as`("no piece bought twice, no lower tier after a higher one: %s", all).doesNotHaveDuplicates()
            assertThat(all.filter { it.startsWith("leather") || it.startsWith("chain") || it.startsWith("iron-warden") }.size).isEqualTo(8)
            assertThat(delivery.delivered.map { it.id.value }).`as`("no tool is ever bought").noneMatch { it.endsWith("pickaxe") || it.endsWith("shovel") }
        }
    }

    @Test
    fun `a failed armour purchase never costs the bot its old piece`() {
        scenario("bot-economy-armour-atomic") {
            val shopsDir = GameSim.defaultContentDir().resolve("shops")
            val catalog = checkNotNull(MatchShopLoaderService().parse(shopsDir).second) { "match-shop.yaml did not load" }
            val offer = catalog.offers.first { it.itemId.value == "chain-guard-helmet" }
            val realDelivery = sim.services.getOrThrow<IItemDelivery>()
            val bot = player("Bot_Atomic", team = "red")
            val bag = checkNotNull(sim.server.view(bot.id)).givenItems
            val stock = sim.services.getOrThrow<IOwnedItemStock>()
            fun purchase(inMatch: Boolean, failNew: Boolean) = MatchShopPurchaseUseCase(
                itemRegistry = sim.services.getOrThrow<IItemRegistry>(),
                server = sim.server,
                stock = { stock },
                delivery = {
                    if (!failNew) realDelivery else object : IItemDelivery {
                        override fun give(target: com.astarworks.astera.core.adapter.api.entity.IMcPlayer, spec: com.astarworks.astera.core.domain.item.ItemSpec, amount: Int, origin: com.astarworks.astera.core.application.port.outbound.item.ItemOrigin) =
                            if (spec.id == offer.itemId) com.astarworks.astera.core.application.port.outbound.item.DeliveryResult.UnknownTemplate(spec.id.value)
                            // SimItemDelivery.give adds ONE inventory entry whatever `amount` is, and the bag counts one entry per
                            // unit, so a refund of a 2-gold price is handed over unit by unit.
                            else (1..amount).map { realDelivery.give(target, spec, 1, origin) }.last()
                        override fun refreshHeld(items: IItemRegistry): Int = 0
                        override fun replace(target: com.astarworks.astera.core.adapter.api.entity.IMcPlayer, from: ItemId, spec: com.astarworks.astera.core.domain.item.ItemSpec) = realDelivery.replace(target, from, spec)
                    }
                },
                inMatch = { inMatch },
            )
            val old = ItemId("leather-scout-helmet")
            repeat(2) { bag += "ore-gold" }
            bag += old.value

            assertThat(purchase(inMatch = false, failNew = false).execute(bot.id, offer, listOf(old))).isInstanceOf(Result.Failure::class.java)
            assertThat(bag).`as`("not in a match: nothing changes").contains(old.value).containsOnlyOnce(old.value)
            assertThat(bag.count { it == "ore-gold" }).isEqualTo(2)

            assertThat(purchase(inMatch = true, failNew = true).execute(bot.id, offer, listOf(old))).isInstanceOf(Result.Failure::class.java)
            assertThat(bag).`as`("delivery of the new piece failed: the old piece is back, no empty slot").containsOnlyOnce(old.value)
            assertThat(bag.count { it == "ore-gold" }).`as`("price refunded").isEqualTo(2)

            assertThat(purchase(inMatch = true, failNew = false).execute(bot.id, offer, listOf(old))).isInstanceOf(Result.Success::class.java)
            assertThat(bag).`as`("success swaps the piece").contains("chain-guard-helmet").doesNotContain(old.value)
        }
    }

    @Test
    fun `a rung counts as done once its item is owned, without the 60 s retry, and the timed retry stays only when ownership cannot be read`() {
        val buys = EconomyPlanner.parseShoppingList(Files.readString(GameSim.defaultContentDir().resolve("shops/match-shop.yaml")))
        val sites = EconomyPlanner.Sites(
            shop = EconomyPlanner.Site(0.0, 0.0, 0.0),
            generators = listOf(EconomyPlanner.Site(10.0, 0.0, 10.0, "ore-iron"), EconomyPlanner.Site(12.0, 0.0, 10.0, "ore-gold")),
            nodes = emptyList(),
        )
        val first = buys.first()
        val rich = mapOf("ore-iron" to 99, "ore-gold" to 99)
        fun walkToShopAndWait(p: EconomyPlanner, owned: Set<String>?): Long {
            var t = 0L
            while (t <= EconomyPlanner.SHOP_WAIT_MS + 1_000) { p.step(t, 0.0, 0.0, rich, false, owned); t += 400 }
            return t
        }

        // Ownership readable and the item is owned: the first rung is passed at once, the bot goes on to the next rung (no waiting at the shop).
        val ownedPlanner = EconomyPlanner(sites, buys)
        val st = checkNotNull(ownedPlanner.step(0, 0.0, 0.0, rich, false, first.items.toSet()))
        assertThat(ownedPlanner.rung).isEqualTo(1)
        assertThat(st.kind).isEqualTo("shop")

        // Ownership readable, price still in the bag, item not owned (shop refused): the rung is dropped after the wait, not retried in 60 s.
        val readable = EconomyPlanner(sites, buys)
        val t = walkToShopAndWait(readable, emptySet())
        assertThat(readable.rung).`as`("the unsellable rung is passed, not snoozed").isGreaterThanOrEqualTo(1)

        // Ownership unreadable (null): rung is NOT passed, it is snoozed and tried again after SHOP_RETRY_MS.
        val blind = EconomyPlanner(sites, listOf(first))
        val tb = walkToShopAndWait(blind, null)
        assertThat(blind.rung).isZero()
        assertThat(blind.step(tb, 0.0, 0.0, rich, false, null)?.kind).`as`("snoozed: not at the shop").isNotEqualTo("shop")
        assertThat(blind.step(tb + EconomyPlanner.SHOP_RETRY_MS + 1, 0.0, 0.0, rich, false, null)?.kind).isEqualTo("shop")
        assertThat(t).isGreaterThan(0L)
    }

    @Test
    fun `a bot buys as soon as ONE piece is affordable instead of saving for the whole tier, cheapest piece first`() {
        val buys = EconomyPlanner.parseShoppingList(Files.readString(GameSim.defaultContentDir().resolve("shops/match-shop.yaml")))
        val sites = EconomyPlanner.Sites(
            shop = EconomyPlanner.Site(0.0, 0.0, 0.0),
            generators = listOf(EconomyPlanner.Site(10.0, 0.0, 10.0, "ore-iron")),
            nodes = emptyList(),
        )
        val price = buys.first().price.getValue("ore-iron")
        assertThat(price).`as`("the cheapest piece costs far less than the 22 iron of the whole tier").isLessThan(22)

        val short = EconomyPlanner(sites, buys)
        assertThat(short.step(0, 20.0, 20.0, mapOf("ore-iron" to price - 1), false, emptySet())?.kind).`as`("one iron short: keep collecting").isEqualTo("collect")

        val enough = EconomyPlanner(sites, buys)
        assertThat(enough.step(0, 20.0, 20.0, mapOf("ore-iron" to price), false, emptySet())?.kind).`as`("one piece affordable: go to the shop").isEqualTo("shop")

        // After the helmet is owned the next piece (boots, same price) is the next target, again without waiting for the rest of the tier.
        val next = EconomyPlanner(sites, buys)
        val owned = setOf(buys[0].items.single())
        assertThat(next.step(0, 20.0, 20.0, mapOf("ore-iron" to price), false, owned)?.kind).isEqualTo("shop")
        assertThat(next.rung).isEqualTo(1)
        // A better helmet already worn passes the leather helmet rung without a shop trip.
        val better = EconomyPlanner(sites, buys)
        better.step(0, 20.0, 20.0, emptyMap(), false, setOf("iron-warden-helmet"))
        assertThat(better.rung).`as`("leather + chain-guard + iron-warden helmet rungs are all covered by the worn iron-warden helmet").isEqualTo(3)
    }
}
