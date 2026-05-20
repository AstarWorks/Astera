package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IStatusEffectContainer] for use case tests.
 *
 * Stacking semantics follow the spec: if the effect is not stackable,
 * the existing instance is replaced (duration refresh); if stackable,
 * a new instance is added up to the spec's maxStacks cap.
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
        if (!spec.stackable) {
            set.removeIf { it.spec.id == spec.id }
        } else {
            val currentCount = set.count { it.spec.id == spec.id }
            if (currentCount >= spec.maxStacks) return
        }
        set += instance
    }

    override fun remove(playerId: PlayerId, effectId: StatusEffectId) {
        store[playerId]?.removeIf { it.spec.id == effectId }
    }

    override fun clear(playerId: PlayerId) {
        store.remove(playerId)
    }
}
