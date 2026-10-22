package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IStatusEffectContainer] for use case tests.
 *
 * Stacking semantics follow the spec (and the Paper / simulator containers): a
 * non-stackable effect is replaced (duration refresh); a stackable one keeps a
 * single instance whose `currentStacks` grows by the applied instance's
 * `currentStacks`, capped at the spec's maxStacks.
 */
public class FakeStatusEffectContainer : IStatusEffectContainer {

    private val store: MutableMap<PlayerId, MutableSet<StatusEffectInstance>> = mutableMapOf()

    override fun active(playerId: PlayerId): Collection<StatusEffectInstance> =
        store[playerId]?.toList() ?: emptyList()

    override fun has(playerId: PlayerId, effectId: StatusEffectId): Boolean =
        store[playerId]?.any { it.spec.id == effectId } == true

    override fun apply(instance: StatusEffectInstance) {
        val set = store.getOrPut(instance.targetPlayerId) { mutableSetOf() }
        val spec = instance.spec
        val existing = set.firstOrNull { it.spec.id == spec.id }
        set.removeIf { it.spec.id == spec.id }
        val stacks = if (!spec.stackable) 1 else ((existing?.currentStacks ?: 0) + instance.currentStacks).coerceAtMost(spec.maxStacks)
        set += instance.copy(currentStacks = stacks)
    }

    override fun removeStacks(playerId: PlayerId, effectId: StatusEffectId, count: Int) {
        val set = store[playerId] ?: return
        val existing = set.firstOrNull { it.spec.id == effectId } ?: return
        set.remove(existing)
        if (count < existing.currentStacks) set += existing.copy(currentStacks = existing.currentStacks - count)
    }

    override fun remove(playerId: PlayerId, effectId: StatusEffectId) {
        store[playerId]?.removeIf { it.spec.id == effectId }
    }

    override fun clear(playerId: PlayerId) {
        store.remove(playerId)
    }
}
