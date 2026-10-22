package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.adapter.api.event.IMcEvent.InputKind
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.service.ItemInputTriggers
import com.astarworks.astera.items.application.usecase.HandleItemTriggerUseCase

/**
 * Vendor-neutral input-to-skill sink (Wave _items-1, ADR-0073; WP1).
 *
 * Receives [IMcEvent]s from the Paper listener (or the headless simulator),
 * resolves the Astera item the input acts on ([IMcEvent.PlayerInput.itemId],
 * falling back to the main-hand PDC read via [IPaperItemIdResolver]), maps the
 * input to [com.astarworks.astera.core.domain.skill.SkillTrigger] candidates
 * ([ItemInputTriggers]) and fires the first candidate the item binds.
 *
 * [handle] returns `true` when a binding consumed the input; the Paper
 * listener then cancels the vanilla side effect (item drop, block break,
 * crossbow load, ...).
 *
 * Imports no Bukkit (ADR-0022); the Bukkit reads happen in the resolver and
 * the Paper listener.
 *
 * @param nowTick monotonic server tick, used to detect a held right click.
 */
public class ItemSkillTriggerListener(
    private val handleTrigger: HandleItemTriggerUseCase,
    private val itemIdResolver: IPaperItemIdResolver,
    private val nowTick: () -> Long,
) {

    private val lastRightClickTick = HashMap<PlayerId, Long>()

    /** @return `true` if the input was consumed by an item binding. */
    public fun handle(event: IMcEvent): Boolean {
        when (event) {
            is IMcEvent.PlayerQuit -> {
                lastRightClickTick.remove(event.player.id)
                return false
            }
            is IMcEvent.PlayerInput -> Unit
            is IMcEvent.PlayerJoined,
            is IMcEvent.BlockPlaced,
            is IMcEvent.BlockBroken,
            -> return false
        }

        val player = event.player
        val heldRepeat = event.kind == InputKind.RIGHT_CLICK && noteRightClick(player.id)
        val itemId = event.itemId ?: itemIdResolver.heldItemId(player.id) ?: return false

        for (trigger in ItemInputTriggers.candidates(event.kind, event.sneaking, heldRepeat)) {
            if (handleTrigger.execute(player.id, itemId, trigger, player.location)) return true
        }
        return false
    }

    /** Records this right click; `true` when it follows the previous one closely enough to be a hold. */
    private fun noteRightClick(id: PlayerId): Boolean {
        val now = nowTick()
        val previous = lastRightClickTick.put(id, now)
        return previous != null && now - previous <= ItemInputTriggers.HOLD_GAP_TICKS
    }
}
