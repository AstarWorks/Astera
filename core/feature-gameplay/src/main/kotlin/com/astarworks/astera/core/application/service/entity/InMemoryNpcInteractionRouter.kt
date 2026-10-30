package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [INpcInteractionRouter]. Wave 5l shipped at priority 0;
 * no Paper overlay is planned — interaction is "pure data" routing that
 * doesn't need a vendor-specific impl.
 *
 * Backing store is a [ConcurrentHashMap] so the Paper interact listener
 * (Wave 6b) can race against an admin's `/astera-npc bind` mutation
 * safely.
 */
public class InMemoryNpcInteractionRouter : INpcInteractionRouter {

    private val bindings: ConcurrentHashMap<Pair<EntityHandle, NpcInteractionClick>, GuiIntent> =
        ConcurrentHashMap()

    override fun bindRightClick(handle: EntityHandle, intent: GuiIntent) {
        bindings[handle to NpcInteractionClick.RIGHT] = intent
    }

    override fun bindLeftClick(handle: EntityHandle, intent: GuiIntent) {
        bindings[handle to NpcInteractionClick.LEFT] = intent
    }

    override fun resolve(handle: EntityHandle, click: NpcInteractionClick): GuiIntent? =
        bindings[handle to click]

    private val users: ConcurrentHashMap<EntityHandle, MutableSet<PlayerId>> = ConcurrentHashMap()

    override fun recordUse(player: PlayerId, handle: EntityHandle) {
        users.computeIfAbsent(handle) { ConcurrentHashMap.newKeySet() }.add(player)
    }

    override fun usersOf(handle: EntityHandle): Set<PlayerId> = users[handle]?.toSet().orEmpty()

    override fun unbind(handle: EntityHandle) {
        bindings.keys.removeIf { it.first == handle }
        users.remove(handle)
    }

    override fun all(): Map<Pair<EntityHandle, NpcInteractionClick>, GuiIntent> =
        bindings.toMap()

    /** Test-only: clear all bindings. */
    public fun clear() {
        bindings.clear()
        users.clear()
    }
}
