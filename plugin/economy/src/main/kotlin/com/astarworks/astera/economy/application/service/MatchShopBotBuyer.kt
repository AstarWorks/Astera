package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IBotPlayers
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.usecase.shop.MatchShopPurchaseUseCase
import com.astarworks.astera.economy.domain.matchshop.MatchShopCatalog
import com.astarworks.astera.economy.domain.matchshop.MatchShopOffer
import com.astarworks.astera.economy.domain.matchshop.MatchShopTab
import org.slf4j.LoggerFactory

/**
 * Bots (protocol clients) cannot click the shop GUI, so this is the server-side stand-in for the click, under exactly the
 * rules a player has: the bot must stand next to its OWN team's base shop NPC, it pays the catalog price in ore it really
 * carries, and it buys armour and weapon upgrades of weapons it really holds (never tools: those follow its Norma level) ([MatchShopPurchaseUseCase] is the same code path as
 * the GUI buy). No free stats: a bot without ore at its base buys nothing.
 *
 * [tick] is driven by a repeating task; one pass walks every bot of every running match and runs [shop] for each one at its shop.
 *
 * Order (matches the planner's ladder in the bot runner): base-ore armour tiers, then weapon upgrades, then Mid-ore armour tiers.
 * Armour is bought as a whole set of the next tier (every slot that still holds a lower tier or nothing) before the next weapon
 * tier; a tier equal to or below the one owned in a slot is never bought, and the replaced piece is handed in.
 */
public class MatchShopBotBuyer(
    private val matches: IMatchRepository,
    private val stages: IStageRegistry,
    private val server: IMcServer,
    private val bots: () -> IBotPlayers?,
    private val purchase: MatchShopPurchaseUseCase,
    private val stock: () -> IOwnedItemStock?,
    private val catalog: () -> MatchShopCatalog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One armour tier of the catalog: its pieces by slot, [rank] higher = better, [mid] = priced in Mid ore. */
    private class Tier(val rank: Int, val mid: Boolean, val pieces: Map<String, MatchShopOffer>)

    /**
     * Armour tiers ranked from the catalog itself: a tier is the offers sharing an id prefix (`<tier>-helmet`, ...); a tier
     * priced in a better ore is better (iron < gold < diamond < emerald), equal ore falls back to the summed price.
     */
    private fun armourTiers(cat: MatchShopCatalog): List<Tier> {
        val byTier = LinkedHashMap<String, MutableMap<String, MatchShopOffer>>()
        for (offer in cat.tab(MatchShopTab.ARMOR)) {
            val slot = ARMOUR_SLOT.find(offer.itemId.value)?.value ?: continue
            byTier.getOrPut(offer.itemId.value.removeSuffix("-$slot")) { LinkedHashMap() }[slot] = offer
        }
        fun oreRank(o: MatchShopOffer) = o.price.keys.maxOf { ORE_ORDER.indexOf(it.value) }
        val ranked = byTier.values.sortedWith(
            compareBy<Map<String, MatchShopOffer>>({ t -> t.values.maxOf(::oreRank) }, { t -> t.values.sumOf { it.price.values.sum() } }),
        )
        return ranked.mapIndexed { i, pieces ->
            Tier(i, pieces.values.any { o -> o.price.keys.any { it.value in MID_ORES } }, pieces)
        }
    }

    private fun affordable(member: PlayerId, owned: IOwnedItemStock, price: Map<ItemId, Int>): Boolean =
        price.all { (ore, count) -> owned.count(member, ore) >= count }

    private fun summed(offers: Collection<MatchShopOffer>): Map<ItemId, Int> {
        val out = HashMap<ItemId, Int>()
        offers.forEach { o -> o.price.forEach { (ore, n) -> out.merge(ore, n, Int::plus) } }
        return out
    }

    /** Rank of the best armour piece the bot holds in [slot] (worn or carried), -1 when none. */
    private fun ownedRank(member: PlayerId, owned: IOwnedItemStock, tiers: List<Tier>, slot: String): Int =
        tiers.filter { t -> t.pieces[slot]?.let { owned.count(member, it.itemId) >= 1 } == true }.maxOfOrNull { it.rank } ?: -1

    private fun buy(member: PlayerId, name: String, offer: MatchShopOffer) {
        when (val result = purchase.execute(member, offer)) {
            is Result.Success -> log.info("Bot {} bought {} at its base shop", name, offer.itemId.value)
            is Result.Failure -> log.warn("Bot {} could not buy {}: {}", name, offer.itemId.value, result.error)
        }
    }

    /**
     * Within one ore group the bot buys the BEST tier whose missing pieces it can pay in full (a lower tier it could also pay
     * would be replaced at once, so it is skipped). Each worn lower-tier piece is handed in first so the new one is equipped
     * into the freed armour slot.
     */
    private fun armourGroup(member: PlayerId, name: String, owned: IOwnedItemStock, tiers: List<Tier>, mid: Boolean) {
        var best: Pair<Tier, List<Map.Entry<String, MatchShopOffer>>>? = null
        for (tier in tiers.filter { it.mid == mid }) {
            val need = tier.pieces.entries.filter { (slot, _) -> ownedRank(member, owned, tiers, slot) < tier.rank }
            if (need.isEmpty()) continue
            if (affordable(member, owned, summed(need.map { it.value }))) best = tier to need
        }
        val (tier, need) = best ?: return
        for ((slot, offer) in need) {
            for (lower in tiers) {
                if (lower.rank >= tier.rank) continue
                val old = lower.pieces[slot] ?: continue
                while (owned.count(member, old.itemId) > 0 && owned.remove(member, old.itemId, 1)) { /* hand in every copy */ }
            }
            buy(member, name, offer)
        }
    }

    private fun shop(member: PlayerId, name: String, owned: IOwnedItemStock, tiers: List<Tier>, upgrades: List<MatchShopOffer>) {
        armourGroup(member, name, owned, tiers, mid = false)
        for (offer in upgrades) {
            val base = offer.upgradeFrom ?: continue
            if (owned.count(member, base) < 1) continue
            if (!affordable(member, owned, offer.price)) continue
            buy(member, name, offer)
        }
        armourGroup(member, name, owned, tiers, mid = true)
    }

    public fun tick() {
        val isBot = bots() ?: return
        val owned = stock() ?: return
        val cat = catalog()
        val tiers = armourTiers(cat)
        val upgrades = cat.tab(MatchShopTab.UPGRADES)
        if (tiers.isEmpty() && upgrades.isEmpty()) return
        for (match in matches.findActiveMatches()) {
            val stage = stages.find(match.stageId) ?: continue
            for (team in match.teams) {
                val shops = stage.shopNpcs.filter { it.team == team.id }
                if (shops.isEmpty()) continue
                for (member in team.members) {
                    if (!isBot.isBot(member)) continue
                    val player = server.findPlayer(member) ?: continue
                    val at = player.location
                    val atShop = shops.any { npc ->
                        Math.hypot(at.x - (npc.x + stage.origin.x), at.z - (npc.z + stage.origin.z)) <= SHOP_REACH &&
                            Math.abs(at.y - npc.y) <= SHOP_REACH_Y
                    }
                    if (!atShop) continue
                    shop(member, player.name, owned, tiers, upgrades)
                }
            }
        }
    }

    public companion object {
        /** How close a bot must be to its team's shop NPC (a player right-clicks from about this far). */
        public const val SHOP_REACH: Double = 5.0
        public const val SHOP_REACH_Y: Double = 3.0

        private val ORE_ORDER = listOf("ore-iron", "ore-gold", "ore-diamond", "ore-emerald")
        private val MID_ORES = setOf("ore-diamond", "ore-emerald")
        private val ARMOUR_SLOT = Regex("(helmet|chestplate|leggings|boots)$")
    }
}
