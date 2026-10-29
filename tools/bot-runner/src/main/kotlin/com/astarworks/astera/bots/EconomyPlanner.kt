package com.astarworks.astera.bots

import kotlin.math.hypot

/**
 * The lull-time economy of a bot: earn the ore of the next weapon upgrade the way a player does and spend it at the OWN base shop
 * (`content/shops/match-shop.yaml`, upgrades tab: tier 2 gold, tier 3 diamond, tier 4 emerald). Pure and deterministic like
 * [ModePlanner]: the clock and the carried ore are passed in, so a game-sim scenario drives the very planner the live [BotBrain] uses.
 *
 * The ladder is the shop's distinct upgrade prices in file order ([parseLadder]); the bot works on the first rung it has not
 * passed yet. A rung is one of:
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

    /** Rungs of the ladder already passed (bought or given up). */
    var rung = 0; private set
    val done: Boolean get() = rung >= ladder.size

    private val blockedUntil = HashMap<Triple<Int, Int, Int>, Long>()
    private val oreBlockedUntil = HashMap<String, Long>()
    private var mineTarget: Triple<Int, Int, Int>? = null
    private var mineSince = 0L
    private var mineOreAt = 0
    private var shopSince = 0L
    private var toShop = false

    /** Nodes dropped as useless so far (reports, scenarios). */
    var nodesGivenUp = 0; private set

    /** The next economy step for a bot at ([x], [z]) carrying [have] (ore item id -> count); null = nothing to do (ladder done or no source). */
    fun step(now: Long, x: Double, z: Double, have: Map<String, Int>, hasPickaxe: Boolean): Step? {
        if (done) return null
        val price = ladder[rung]
        if (price.all { (ore, n) -> (have[ore] ?: 0) >= n }) return shopStep(now, x, z, have, hasPickaxe)
        val shop = sites.shop
        if (shopSince != 0L || (toShop && shop != null && hypot(shop.x - x, shop.z - z) <= SHOP_RADIUS + 0.5)) { // the price left the bag at the shop: the server-side buyer bought this rung
            shopSince = 0L
            toShop = false
            rung++
            return step(now, x, z, have, hasPickaxe)
        }
        val lacking = price.entries.first { (have[it.key] ?: 0) < it.value }.key
        val own = sites.generators.filter { it.ore == lacking }
        if (own.isNotEmpty()) {
            mineTarget = null
            val g = own.minBy { hypot(it.x - x, it.z - z) }
            return Step("collect", g.x, g.y, g.z, COLLECT_RADIUS, lacking)
        }
        if (!hasPickaxe) return null
        return mineStep(now, x, z, lacking, have[lacking] ?: 0)
    }

    private fun shopStep(now: Long, x: Double, z: Double, have: Map<String, Int>, hasPickaxe: Boolean): Step? {
        mineTarget = null
        val shop = sites.shop ?: return null
        val s = Step("shop", shop.x, shop.y, shop.z, SHOP_RADIUS, "")
        toShop = true
        if (hypot(shop.x - x, shop.z - z) > SHOP_RADIUS + 0.5) { shopSince = 0L; return s }
        if (shopSince == 0L) shopSince = now
        if (now - shopSince < SHOP_WAIT_MS) return s
        shopSince = 0L
        toShop = false
        rung++
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
        private val UPGRADES_TAB = Regex("(?m)^\\s*tab:\\s*upgrades\\s*$")
        private val OFFER_START = Regex("(?m)^\\s*-\\s*item_id:")

        /** The distinct prices of the `tab: upgrades` offers of `match-shop.yaml`, in file order (one rung each). */
        fun parseLadder(shopYaml: String): List<Map<String, Int>> {
            val out = ArrayList<Map<String, Int>>()
            for (offer in shopYaml.split(OFFER_START).drop(1)) {
                if (!UPGRADES_TAB.containsMatchIn(offer)) continue
                val price = PRICE.find(offer)?.groupValues?.get(1) ?: continue
                val map = PAIR.findAll(price).associate { it.groupValues[1] to it.groupValues[2].toInt() }
                if (map.isNotEmpty() && map !in out) out += map
            }
            return out
        }
    }
}
