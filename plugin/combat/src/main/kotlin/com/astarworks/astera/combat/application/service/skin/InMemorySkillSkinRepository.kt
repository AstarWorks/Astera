package com.astarworks.astera.combat.application.service.skin

import com.astarworks.astera.combat.application.port.outbound.skin.ISkillSkinRepository
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/** Default [ISkillSkinRepository] (the Postgres one overrides it at priority 100). */
public class InMemorySkillSkinRepository : ISkillSkinRepository {
    private class Slot {
        val owned: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var selected: String? = null
    }

    private val slots = ConcurrentHashMap<Pair<PlayerId, String>, Slot>()

    override fun selected(player: PlayerId, baseSkillId: String): String? = slots[player to baseSkillId]?.selected

    override fun owned(player: PlayerId, baseSkillId: String): Set<String> = slots[player to baseSkillId]?.owned?.toSet() ?: emptySet()

    override fun grant(player: PlayerId, baseSkillId: String, skin: String) {
        slots.computeIfAbsent(player to baseSkillId) { Slot() }.owned += skin
    }

    override fun revoke(player: PlayerId, baseSkillId: String, skin: String) {
        val slot = slots[player to baseSkillId] ?: return
        slot.owned -= skin
        if (slot.selected == skin) slot.selected = null
    }

    override fun select(player: PlayerId, baseSkillId: String, skin: String?): Boolean {
        if (skin == null) {
            slots[player to baseSkillId]?.selected = null
            return true
        }
        val slot = slots[player to baseSkillId]?.takeIf { skin in it.owned } ?: return false
        slot.selected = skin
        return true
    }
}
