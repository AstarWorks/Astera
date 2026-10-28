package com.astarworks.astera.match.application.service.loadout

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.IMatchEntryLoadout
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemRemoval
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.WeaponCategory
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.config.BotLoadoutPool
import com.astarworks.astera.match.domain.loadout.BotLoadout
import com.astarworks.astera.match.domain.loadout.BotLoadoutPicker
import com.astarworks.astera.match.domain.loadout.BotWeapon
import com.astarworks.astera.match.domain.loadout.BotWeaponFamily
import com.astarworks.astera.match.domain.norma.NormaConfig
import org.slf4j.LoggerFactory
import java.util.Objects
import kotlin.random.Random

/**
 * Bots are protocol clients (`tools/bot-runner`) and cannot give themselves items, so the match hands each bot a
 * loadout of two weapons from two different families, drawn from the content pool (`content/bot-loadouts/`).
 *
 * Runs through Core's [IMatchEntryLoadout] hook right after the entry inventory snapshot was taken: what is added here is
 * not part of the snapshot, so the match-end restore gives the bot its pre-match inventory back (the same mechanism as the
 * loaner kit). Weapons the bot carried are taken out first so the loadout is exactly the two drawn weapons.
 *
 *  - A player is a bot when its name starts with the pool's `bot_name_prefix` (the runner's accounts; the game server
 *    never sees the Redis bot status, only the login name).
 *  - Only weapons usable at Norma level 1 are drawn (the rarity gate of the match, ADR-0292).
 *  - Draws are seeded (pool `seed`, else the match id) and avoid a pair another bot of the match already got, so bots of
 *    one match differ whenever the pool allows it. A rejoin re-arms the same loadout.
 */
public class BotLoadoutService(
    private val pool: () -> BotLoadoutPool?,
    private val items: IItemRegistry,
    private val owned: () -> IOwnedItemQuery?,
    private val removal: () -> IOwnedItemRemoval?,
    private val delivery: () -> IItemDelivery?,
    private val server: IMcServer,
    private val matchOf: (PlayerId) -> MatchId?,
    private val normaOf: (MatchId) -> NormaConfig,
) : IMatchEntryLoadout {

    private val log = LoggerFactory.getLogger(javaClass)

    private val assignments: MutableMap<MatchId, MutableMap<PlayerId, BotLoadout>> = HashMap()

    /** Problems of [pool] against the item registry: unknown ids, items that are not weapons, items without skill bindings. */
    public fun validate(pool: BotLoadoutPool): List<String> =
        pool.families.flatMap { (family, ids) ->
            ids.mapNotNull { id ->
                val spec = items.find(id)
                when {
                    spec == null -> "${family.key}: unknown item '${id.value}'"
                    spec.skillBindings.isEmpty() -> "${family.key}: item '${id.value}' has no skill bindings"
                    WeaponCategory.of(spec) == null -> "${family.key}: item '${id.value}' is not a weapon (no ammo / energy resource)"
                    else -> null
                }
            }
        }

    /** Pool entries per family a bot of a match with [norma] may be handed (rarity open at level 1). */
    public fun eligibleCounts(pool: BotLoadoutPool, norma: NormaConfig): Map<BotWeaponFamily, Int> =
        BotWeaponFamily.entries.associateWith { family -> eligible(pool, norma).count { it.family == family } }

    private fun eligible(pool: BotLoadoutPool, norma: NormaConfig): List<BotWeapon> =
        pool.families.flatMap { (family, ids) ->
            ids.mapNotNull { id ->
                val spec = items.find(id) ?: return@mapNotNull null
                val category = WeaponCategory.of(spec) ?: return@mapNotNull null
                if (spec.skillBindings.isEmpty() || norma.levelFor(spec.rarity) > START_LEVEL) null else BotWeapon(id, family, category)
            }
        }

    @Synchronized
    override fun grantOnEntry(playerId: PlayerId) {
        val pool = pool() ?: return
        val player = server.findPlayer(playerId) ?: return
        if (!player.name.startsWith(pool.botNamePrefix)) return
        val matchId = matchOf(playerId) ?: return
        val perMatch = assignments.getOrPut(matchId) { LinkedHashMap() }
        val loadout = perMatch[playerId] ?: draw(pool, matchId, playerId, perMatch)?.also { perMatch[playerId] = it } ?: return
        val query = owned() ?: return
        val itemDelivery = delivery() ?: return
        val carried = query.itemIds(playerId)
        val take = removal()
        carried.filter { id -> id !in loadout.items && items.find(id)?.let { WeaponCategory.of(it) != null } == true }
            .forEach { take?.removeOne(playerId, it) }
        loadout.items.filter { it !in carried }.mapNotNull { items.find(it) }.forEach { itemDelivery.give(player, it, 1) }
        log.info("Bot {} carries {} for match {}", player.name, loadout.items.map { it.value }, matchId.value)
    }

    private fun draw(pool: BotLoadoutPool, matchId: MatchId, playerId: PlayerId, perMatch: Map<PlayerId, BotLoadout>): BotLoadout? {
        val problems = validate(pool)
        if (problems.isNotEmpty()) log.error("Bot loadout pool has invalid entries (skipped): {}", problems)
        val weapons = eligible(pool, normaOf(matchId))
        val random = Random(Objects.hash(pool.seed ?: matchId.value.hashCode().toLong(), playerId.uuid))
        val picked = BotLoadoutPicker.pick(weapons, perMatch.values.map { it.key }.toSet(), random)
        if (picked == null) log.error("No bot loadout can be drawn for {} in match {}: pool has no valid pair", playerId, matchId.value)
        return picked
    }

    /** The loadout [playerId] was handed in [matchId], if any. */
    @Synchronized
    public fun loadoutOf(matchId: MatchId, playerId: PlayerId): List<ItemId>? = assignments[matchId]?.get(playerId)?.items

    @Synchronized
    public fun onMatchEnded(matchId: MatchId) {
        assignments.remove(matchId)
    }

    private companion object {
        /** Bots enter a match at Norma level 1. */
        const val START_LEVEL = 1
    }
}
