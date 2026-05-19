package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Binds (or clears) a hotbar quick-cast slot to a skill (Wave 5e, RTM issue
 * #15).
 *
 * `skillId == null` clears the binding; non-null assigns. Slot must be in
 * `1..9` (the Bukkit hotbar) — otherwise [SettingsError.InvalidSlot]. Skill
 * ids are passed as raw `String` because Core may not depend on Combat per
 * ADR-0022 sibling-isolation; the combat plugin resolves the string at
 * use-site.
 */
public class SetQuickCastSlotUseCase(private val settings: IPlayerSettingsRepository) {

    public fun set(playerId: PlayerId, slot: Int, skillId: String?): Result<Unit, SettingsError> {
        if (slot !in 1..9) {
            return Result.Failure(SettingsError.InvalidSlot(slot))
        }
        val current = settings.findOrDefault(playerId)
        val next = current.quickCastSlots.toMutableMap().also { mut ->
            if (skillId == null) mut.remove(slot) else mut[slot] = skillId
        }
        settings.upsert(current.copy(quickCastSlots = next.toMap()))
        return Result.Success(Unit)
    }
}
