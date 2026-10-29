package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IBotPlayers
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.economy.application.usecase.shop.MatchShopPurchaseUseCase
import com.astarworks.astera.economy.domain.matchshop.MatchShopCatalog
import com.astarworks.astera.economy.domain.matchshop.MatchShopTab
import org.slf4j.LoggerFactory

/**
 * Bots (protocol clients) cannot click the shop GUI, so this is the server-side stand-in for the click, under exactly the
 * rules a player has: the bot must stand next to its OWN team's base shop NPC, it pays the catalog price in ore it really
 * carries, and it only buys weapon upgrades of weapons it really holds ([MatchShopPurchaseUseCase] is the same code path as
 * the GUI buy). No free stats: a bot without ore at its base buys nothing.
 *
 * [tick] is driven by a repeating task; one pass walks every bot of every running match and buys, in catalog order, each
 * upgrade the bot can pay for.
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

    public fun tick() {
        val isBot = bots() ?: return
        val owned = stock() ?: return
        val upgrades = catalog().tab(MatchShopTab.UPGRADES)
        if (upgrades.isEmpty()) return
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
                    for (offer in upgrades) {
                        val base = offer.upgradeFrom ?: continue
                        if (owned.count(member, base) < 1) continue
                        if (offer.price.any { (ore, count) -> owned.count(member, ore) < count }) continue
                        when (val result = purchase.execute(member, offer)) {
                            is Result.Success -> log.info("Bot {} bought {} at its base shop", player.name, offer.itemId.value)
                            is Result.Failure -> log.warn("Bot {} could not buy {}: {}", player.name, offer.itemId.value, result.error)
                        }
                    }
                }
            }
        }
    }

    public companion object {
        /** How close a bot must be to its team's shop NPC (a player right-clicks from about this far). */
        public const val SHOP_REACH: Double = 5.0
        public const val SHOP_REACH_Y: Double = 3.0
    }
}
