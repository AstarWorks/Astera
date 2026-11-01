package com.astarworks.astera.bots

import kotlin.math.hypot

/**
 * The lull-time economy of a bot: earn the ore of the next useful buy the way a player does and spend it at the OWN base shop
 * (`content/shops/match-shop.yaml`: iron pick, armour tiers, weapon upgrades; see [parseShoppingList]). Pure and deterministic like
 * [ModePlanner]: the clock and the carried ore are passed in, so a game-sim scenario drives the very planner the live [BotBrain] uses.
 *
 * The ladder is the shopping list's prices in priority order ([parseLadder]). A rung the bag already pays goes to the shop first;
 * otherwise the bot works on the first unpassed rung whose missing ore it can actually get (an unreachable rung is skipped, never
 * waited on). Working on a rung is one of:
 *  - `shop`: the price is in the bag -> walk to the own team's shop NPC and stand there (the server-side buyer then buys);
 *    after [SHOP_WAIT_MS] the rung counts as passed whether or not ore left the bag (a bot without the base weapon cannot buy);
 *  - `collect`: an ore the own base generators drop -> stand at the nearest one and let the drops come to it;
 *  - `mine`: an ore only Mid nodes give -> dig the nearest pickaxe node of that ore. A node that gave nothing for [MINE_GIVE_UP_MS]
 *    (Norma level too low, regrowing, unreachable) is dropped for [BLOCK_MS]; the whole ore then rests for [ORE_RETRY_MS] (the
 *    level gate hits every node of an ore alike), during which [step] returns null and the objective play has the bot.
 * It never gives the bot anything: ore comes from the world, tiers come from the shop.
 */
class EconomyPlanner(
    private val sites: Sites,
    private val ladder: List<Map<String, Int>>,
) {
    /** One ore source or the shop, in world coordinates ([ore] = item id of what it gives, "" for the shop). */
    data class Site(val x: Double, val y: Double, val z: Double, val ore: String = "")

    data class Sites(val shop: Site?, val generators: List<Site>, val nodes: List<Site>) {
        fun shifted(dx: Double, dz: Double) = Sites(
            shop?.let { it.copy(x = it.x + dx, z = it.z + dz) },
            generators.map { it.copy(x = it.x + dx, z = it.z + dz) },
            nodes.map { it.copy(x = it.x + dx, z = it.z + dz) },
        )
    }

    /** [kind]: `shop`, `collect` or `mine`; [strike] is the node block to dig on arrival (mine only). */
    data class Step(val kind: String, val x: Double, val y: Double, val z: Double, val arrive: Double, val ore: String, val strike: Triple<Int, Int, Int>? = null)

    private val passed = BooleanArray(ladder.size)

    /** Rungs of the ladder already passed (bought or given up). */
    val rung: Int get() = passed.count { it }
    val done: Boolean get() = passed.all { it }

    private val blockedUntil = HashMap<Triple<Int, Int, Int>, Long>()
    private val oreBlockedUntil = HashMap<String, Long>()
    private var mineTarget: Triple<Int, Int, Int>? = null
    private var mineSince = 0L
    private var mineOreAt = 0
    private var shopSince = 0L
    private var toShop = false
    private var committed = -1

    /** Nodes dropped as useless so far (reports, scenarios). */
    var nodesGivenUp = 0; private set

    private fun affordable(price: Map<String, Int>, have: Map<String, Int>) = price.all { (ore, n) -> (have[ore] ?: 0) >= n }

    /** Whether the bot can ever fill [ore] now: an own base generator drops it, or it can dig a Mid node of it that is not resting. */
    private fun sourceable(ore: String, now: Long, hasPickaxe: Boolean): Boolean =
        sites.generators.any { it.ore == ore } ||
            (hasPickaxe && (oreBlockedUntil[ore] ?: 0L) <= now && sites.nodes.any { it.ore == ore && (blockedUntil[blockOf(it)] ?: 0L) <= now })

    /**
     * The next economy step for a bot at ([x], [z]) carrying [have] (ore item id -> count); null = nothing to do (ladder done or no source).
     * The ladder is a priority order, not a queue: the first rung the bag already pays goes to the shop; otherwise the bot works on the
     * first rung whose missing ore it can actually get, so an unreachable rung (no generator, no pickaxe, level-gated node) never stalls it.
     */
    fun step(now: Long, x: Double, z: Double, have: Map<String, Int>, hasPickaxe: Boolean): Step? {
        if (done) return null
        val shop = sites.shop
        if (committed >= 0) {
            val price = ladder[committed]
            if (affordable(price, have)) return shopStep(now, x, z, have, hasPickaxe)
            if (shopSince != 0L || (toShop && shop != null && hypot(shop.x - x, shop.z - z) <= SHOP_RADIUS + 0.5)) { // the price left the bag at the shop: the server-side buyer bought this rung
                finishCommitted()
                return step(now, x, z, have, hasPickaxe)
            }
            committed = -1
            toShop = false
        }
        val ready = ladder.indices.firstOrNull { !passed[it] && affordable(ladder[it], have) }
        if (ready != null) {
            committed = ready
            return shopStep(now, x, z, have, hasPickaxe)
        }
        for (i in ladder.indices) {
            if (passed[i]) continue
            val lacking = ladder[i].entries.first { (have[it.key] ?: 0) < it.value }.key
            if (!sourceable(lacking, now, hasPickaxe)) continue
            val own = sites.generators.filter { it.ore == lacking }
            if (own.isNotEmpty()) {
                mineTarget = null
                val g = own.minBy { hypot(it.x - x, it.z - z) }
                return Step("collect", g.x, g.y, g.z, COLLECT_RADIUS, lacking)
            }
            return mineStep(now, x, z, lacking, have[lacking] ?: 0) ?: continue
        }
        return null
    }

    private fun finishCommitted() {
        if (committed >= 0) passed[committed] = true
        committed = -1
        shopSince = 0L
        toShop = false
    }

    private fun shopStep(now: Long, x: Double, z: Double, have: Map<String, Int>, hasPickaxe: Boolean): Step? {
        mineTarget = null
        val shop = sites.shop ?: run { passed[committed] = true; committed = -1; return step(now, x, z, have, hasPickaxe) }
        val s = Step("shop", shop.x, shop.y, shop.z, SHOP_RADIUS, "")
        toShop = true
        if (hypot(shop.x - x, shop.z - z) > SHOP_RADIUS + 0.5) { shopSince = 0L; return s }
        if (shopSince == 0L) shopSince = now
        if (now - shopSince < SHOP_WAIT_MS) return s
        finishCommitted()
        return step(now, x, z, have, hasPickaxe)
    }

    private fun mineStep(now: Long, x: Double, z: Double, ore: String, held: Int): Step? {
        if ((oreBlockedUntil[ore] ?: 0L) > now) return null
        val current = mineTarget
        if (current != null) {
            if (held > mineOreAt) { // it gave ore: that node now regrows, look for the next one
                blockedUntil[current] = now + REGROW_BLOCK_MS
                mineTarget = null
            } else if (mineSince != 0L && now - mineSince > MINE_GIVE_UP_MS) {
                blockedUntil[current] = now + BLOCK_MS
                oreBlockedUntil[ore] = now + ORE_RETRY_MS // a Norma level gate shuts every node of that ore alike: stop wasting lull time on them
                nodesGivenUp++
                mineTarget = null
            }
        }
        var target = mineTarget
        if (target == null) {
            val pick = sites.nodes.filter { it.ore == ore && (blockedUntil[blockOf(it)] ?: 0L) <= now }
                .minByOrNull { hypot(it.x - x, it.z - z) } ?: return null
            target = blockOf(pick)
            mineTarget = target
            mineSince = 0L
            mineOreAt = held
        }
        val site = sites.nodes.first { blockOf(it) == target }
        if (hypot(site.x - x, site.z - z) <= MINE_REACH) { if (mineSince == 0L) mineSince = now } else mineSince = 0L
        return Step("mine", site.x, site.y, site.z, MINE_ARRIVE, ore, target)
    }

    private fun blockOf(s: Site) = Triple(Math.floor(s.x).toInt(), Math.floor(s.y).toInt(), Math.floor(s.z).toInt())

    companion object {
        const val SHOP_RADIUS = 3.5
        const val SHOP_WAIT_MS = 4_000L
        const val COLLECT_RADIUS = 1.5
        const val MINE_ARRIVE = 3.0
        const val MINE_REACH = 4.5
        const val MINE_GIVE_UP_MS = 15_000L
        const val BLOCK_MS = 120_000L
        const val REGROW_BLOCK_MS = 30_000L
        const val ORE_RETRY_MS = 90_000L

        /** Ore item id from a stack's display name (en/ja), or null for anything that is not a shop ore. */
        fun oreOfName(name: String): String? {
            val n = name.lowercase()
            return when {
                "iron ore" in n || "鉄鉱石" in n -> "ore-iron"
                "gold ore" in n || "金鉱石" in n -> "ore-gold"
                "diamond ore" in n || "ダイヤ鉱石" in n -> "ore-diamond"
                "emerald ore" in n || "エメラルド鉱石" in n -> "ore-emerald"
                else -> null
            }
        }

        /** Carried ore by item id from (display name, amount) pairs of the bag. */
        fun oreCounts(stacks: List<Pair<String, Int>>): Map<String, Int> {
            val out = HashMap<String, Int>()
            for ((name, amount) in stacks) oreOfName(name)?.let { out.merge(it, amount, Int::plus) }
            return out
        }

        private val PRICE = Regex("price:\\s*\\{([^}]*)}")
        private val PAIR = Regex("([a-z-]+)\\s*:\\s*(\\d+)")
        private val TAB = Regex("(?m)^\\s*tab:\\s*([a-z]+)\\s*$")
        private val ITEM = Regex("item_id:\\s*([a-z0-9-]+)")
        private val OFFER_START = Regex("(?m)^\\s*-\\s*item_id:")
        private val MID_ORES = setOf("ore-diamond", "ore-emerald")
        private val ARMOR_PIECE = Regex("-(helmet|chestplate|leggings|boots)$")

        /** One shopping-list entry: the shop offers [items] (all bought together) for [price]. */
        data class Buy(val items: List<String>, val price: Map<String, Int>)

        private data class Offer(val item: String, val tab: String, val price: Map<String, Int>)

        private fun offers(shopYaml: String): List<Offer> = shopYaml.split(OFFER_START).drop(1).mapNotNull { block ->
            val item = ITEM.find("item_id:$block")?.groupValues?.get(1) ?: return@mapNotNull null
            val tab = TAB.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val price = PRICE.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val map = PAIR.findAll(price).associate { it.groupValues[1] to it.groupValues[2].toInt() }
            if (map.isEmpty()) null else Offer(item, tab, map)
        }

        /**
         * What a bot saves for, in priority order, read from the shop file: the first pickaxe of the tools tab (it opens the Mid ore
         * nodes), then each armour tier as one bundle (the four pieces, summed price, file order), then the weapon upgrades (one entry
         * per distinct price). The planner skips an entry it cannot fund yet instead of waiting for it.
         */
        fun parseShoppingList(shopYaml: String): List<Buy> {
            val all = offers(shopYaml)
            val out = ArrayList<Buy>()
            all.firstOrNull { it.tab == "tools" && "pickaxe" in it.item }?.let { out += Buy(listOf(it.item), it.price) }
            val tiers = LinkedHashMap<String, MutableList<Offer>>()
            all.filter { it.tab == "armor" }.forEach { tiers.getOrPut(it.item.replace(ARMOR_PIECE, "")) { ArrayList() } += it }
            val armour = tiers.values.map { pieces ->
                val sum = HashMap<String, Int>()
                pieces.forEach { p -> p.price.forEach { (ore, n) -> sum.merge(ore, n, Int::plus) } }
                Buy(pieces.map { it.item }, sum)
            }
            val (midArmour, baseArmour) = armour.partition { buy -> buy.price.keys.any { it in MID_ORES } }
            out += baseArmour
            val upgradePrices = HashSet<Map<String, Int>>()
            for (o in all.filter { it.tab == "upgrades" }) if (upgradePrices.add(o.price)) out += Buy(listOf(o.item), o.price)
            out += midArmour
            return out
        }

        /** The prices of [parseShoppingList], one rung each. */
        fun parseLadder(shopYaml: String): List<Map<String, Int>> = parseShoppingList(shopYaml).map { it.price }
    }
}
