package com.astarworks.astera.combat.application.admin

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.usecase.skill.CastSkillError
import com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Casts any registered skill for a player, as if from an item (F2-5, ADR-0194): the
 * regular [CastSkillUseCase] pipeline (silence, cooldown, phases, dispatcher), minus the
 * resource spend. `force` clears the skill's cooldown first. Shared by `/astera skill
 * cast` and the admin panel's Skills page.
 */
public class AdminSkillCaster(
    private val skills: ISkillRegistry,
    private val castSkill: CastSkillUseCase?,
    private val cooldowns: ICooldownTracker?,
) {

    public fun cast(caster: PlayerId, skillId: SkillId, force: Boolean = false): Result<SkillSpec, CastSkillError> {
        val cast = castSkill ?: return Result.failure(CastSkillError.SkillNotFound(skillId))
        if (skills.find(skillId) == null) return Result.failure(CastSkillError.SkillNotFound(skillId))
        if (force) cooldowns?.reset(caster, CooldownKey.Skill(skillId.value))
        return cast.execute(casterId = caster, skillId = skillId, ignoreResource = true)
    }

    /** All skills, ordered by archetype then id (stable listing for `skill list` and the panel). */
    public fun all(): List<SkillSpec> =
        skills.all().sortedWith(compareBy({ it.archetype.ordinal }, { it.id.value }))
}
