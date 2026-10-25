package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skill.SkillId

/** Why a skill tester could not be handed over. */
public sealed class SkillTesterError {
    /** The `skill-tester` template item is missing from `content/items/`. */
    public data object NoTemplate : SkillTesterError()
    public data object InventoryFull : SkillTesterError()
    public data class Delivery(val detail: String) : SkillTesterError()
}

/**
 * Outbound port: gives a player a "skill tester" item (see
 * [com.astarworks.astera.core.domain.skill.SkillTesterItems]) bound to [SkillId].
 * Implemented by the items plugin (it owns item delivery); consumed by the combat
 * plugin's admin Skills page, which knows the skills but may not import the items
 * plugin (ADR-0022).
 */
public interface ISkillTesterDelivery {
    public fun give(target: IMcPlayer, skill: SkillId): Result<Unit, SkillTesterError>
}
