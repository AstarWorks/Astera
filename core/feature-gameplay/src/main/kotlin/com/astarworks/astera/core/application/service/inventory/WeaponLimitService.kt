package com.astarworks.astera.core.application.service.inventory

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemRemoval
import com.astarworks.astera.core.application.port.outbound.item.IWeaponLimitProvider
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.WeaponCategory
import com.astarworks.astera.core.domain.item.WeaponLimits
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Match weapon restriction (founder playtest "武器制限がない"): the game mode's `weapon_limits`
 * ([WeaponLimits]) caps how many weapons a player carries.
 *
 *  - [enforceOnEntry]: runs right after the entry snapshot (ADR-0198), so trimmed weapons are
 *    not lost - the match-end restore brings them back. The earliest slots win.
 *  - [verdictFor]: asked by the Paper listener before a weapon enters the inventory (ground
 *    pickup, moving a stack in from another inventory).
 *
 * Players outside a match, and modes without `weapon_limits`, are never restricted.
 */
public class WeaponLimitService(
    private val limits: () -> IWeaponLimitProvider?,
    private val owned: () -> IOwnedItemQuery?,
    private val removal: () -> IOwnedItemRemoval?,
    private val items: IItemRegistry,
    private val players: IPlayerGateway,
    private val messages: IMessageRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Outcome of [verdictFor]. */
    public sealed class Verdict {
        public data object Allowed : Verdict()
        public data class Blocked(public val blocker: WeaponLimits.Blocker) : Verdict()
    }

    /** Whether [playerId] may take one more [itemId] into the inventory. Non-weapons are always allowed. */
    public fun verdictFor(playerId: PlayerId, itemId: ItemId): Verdict {
        val cap = activeLimits(playerId) ?: return Verdict.Allowed
        val category = categoryOf(itemId) ?: return Verdict.Allowed
        val held = heldWeapons(playerId).map { it.second }
        return cap.blockerFor(held, category)?.let { Verdict.Blocked(it) } ?: Verdict.Allowed
    }

    /** `true` when both items are weapons of the same family (swapping one for the other keeps the counts). */
    public fun sameCategory(a: ItemId, b: ItemId): Boolean {
        val category = categoryOf(a) ?: return false
        return category == categoryOf(b)
    }

    /** Tells [playerId] why a weapon was refused. */
    public fun notifyBlocked(playerId: PlayerId, blocker: WeaponLimits.Blocker) {
        val (key, placeholders) = when (blocker) {
            is WeaponLimits.Blocker.Total -> BLOCKED_TOTAL to mapOf("cap" to blocker.cap.toString())
            is WeaponLimits.Blocker.Category ->
                BLOCKED_CATEGORY to mapOf("cap" to blocker.cap.toString(), "category" to blocker.category.key)
        }
        players.sendMessage(playerId, messages.render(playerId, key, placeholders))
    }

    /** Removes weapons beyond the cap from [playerId]; @return how many were taken away. */
    public fun enforceOnEntry(playerId: PlayerId): Int {
        val cap = activeLimits(playerId) ?: return 0
        val held = heldWeapons(playerId)
        val excess = cap.excessIndices(held.map { it.second })
        if (excess.isEmpty()) return 0
        val remove = removal() ?: return 0
        val removed = excess.map { held[it].first }.filter { remove.removeOne(playerId, it) }
        if (removed.isNotEmpty()) {
            players.sendMessage(playerId, messages.render(playerId, TRIMMED, mapOf("count" to removed.size.toString())))
            log.info("Trimmed {} to the match weapon cap for {}", removed.map { it.value }, playerId)
        }
        return removed.size
    }

    private fun activeLimits(playerId: PlayerId): WeaponLimits? =
        limits()?.limitsOf(playerId)?.takeUnless { it.isUnlimited }

    private fun categoryOf(itemId: ItemId): WeaponCategory? = items.find(itemId)?.let(WeaponCategory::of)

    private fun heldWeapons(playerId: PlayerId): List<Pair<ItemId, WeaponCategory>> =
        owned()?.itemIds(playerId).orEmpty().mapNotNull { id -> categoryOf(id)?.let { id to it } }

    private companion object {
        val TRIMMED = MessageKey("astera.match.weapon-limit.trimmed")
        val BLOCKED_TOTAL = MessageKey("astera.match.weapon-limit.blocked-total")
        val BLOCKED_CATEGORY = MessageKey("astera.match.weapon-limit.blocked-category")
    }
}
