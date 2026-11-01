package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IBotPlayers
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.economy.application.usecase.shop.MatchShopPurchaseUseCase
import com.astarworks.astera.core.domain.player.PlayerId
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
 * [tick] is driven by a repeating task; one pass walks every bot of every running match and buys, in catalog order, each
 * armour piece (one per slot) and upgrade the bot can pay for, in [wishlist] order.
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

    /** Armour slots each bot bought this match (one piece per slot: a second tier would only sit in the bag). */
    private val armourSlots = java.util.concurrent.ConcurrentHashMap<PlayerId, MutableSet<String>>()

    /**
     * Purchase order of a bot: base-ore armour, then weapon upgrades, then Mid-ore armour. No tool tab (tools follow the Norma
     * level), and nothing the bot already owns.
     */
    private fun wishlist(): List<MatchShopOffer> {
        val cat = catalog()
        val (mid, base) = cat.tab(MatchShopTab.ARMOR).partition { offer -> offer.price.keys.any { it.value in MID_ORES } }
        return base + cat.tab(MatchShopTab.UPGRADES) + mid
    }

    public fun tick() {
        val isBot = bots() ?: return
        val owned = stock() ?: return
        val wishes = wishlist()
        if (wishes.isEmpty()) return
        val present = HashSet<PlayerId>()
        for (match in matches.findActiveMatches()) {
            val stage = stages.find(match.stageId) ?: continue
            for (team in match.teams) {
                val shops = stage.shopNpcs.filter { it.team == team.id }
                if (shops.isEmpty()) continue
                for (member in team.members) {
                    if (!isBot.isBot(member)) continue
                    present += member
                    val player = server.findPlayer(member) ?: continue
                    val at = player.location
                    val atShop = shops.any { npc ->
                        Math.hypot(at.x - (npc.x + stage.origin.x), at.z - (npc.z + stage.origin.z)) <= SHOP_REACH &&
                            Math.abs(at.y - npc.y) <= SHOP_REACH_Y
                    }
                    if (!atShop) continue
                    val slots = armourSlots.getOrPut(member) { HashSet() }
                    for (offer in wishes) {
                        val slot = if (offer.tab == MatchShopTab.ARMOR) ARMOUR_SLOT.find(offer.itemId.value)?.value else null
                        if (offer.tab == MatchShopTab.ARMOR && (slot == null || slot in slots)) continue
                        val base = offer.upgradeFrom
                        if (base != null && owned.count(member, base) < 1) continue
                        if (base == null && owned.count(member, offer.itemId) >= 1) continue
                        if (offer.price.any { (ore, count) -> owned.count(member, ore) < count }) continue
                        when (val result = purchase.execute(member, offer)) {
                            is Result.Success -> {
                                if (slot != null) slots += slot
                                log.info("Bot {} bought {} at its base shop", player.name, offer.itemId.value)
                            }
                            is Result.Failure -> log.warn("Bot {} could not buy {}: {}", player.name, offer.itemId.value, result.error)
                        }
                    }
                }
            }
        }
        armourSlots.keys.retainAll(present)
    }

    public companion object {
        /** How close a bot must be to its team's shop NPC (a player right-clicks from about this far). */
        public const val SHOP_REACH: Double = 5.0
        public const val SHOP_REACH_Y: Double = 3.0

        private val MID_ORES = setOf("ore-diamond", "ore-emerald")
        private val ARMOUR_SLOT = Regex("(helmet|chestplate|leggings|boots)$")
    }
}
