package com.astarworks.astera.combat.application.usecase.skin

import com.astarworks.astera.combat.application.port.outbound.skin.ISkillSkinRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill

/** Why a skin could not be granted or selected. */
public sealed interface SkinError {
    /** The catalog has no `<base>@<skin>`. */
    public data class UnknownVariant(val baseSkillId: String, val skin: String) : SkinError

    /** `<base>@<skin>` exists but its patch kind is [kind], not `skin`: a `mechanic` or `balance` variant is another skill, not a look. */
    public data class NotASkin(val baseSkillId: String, val skin: String, val kind: String) : SkinError

    /** The player does not own the skin. */
    public data class NotOwned(val baseSkillId: String, val skin: String) : SkinError
}

/**
 * Grants, revokes and selects skills' skins (engine-gaps-v2 2.3). Every skin id is checked against the live catalog, so a typo or a
 * non-skin variant is refused here instead of being stored and silently casting the base later. Purchase flows call [grant].
 */
public class SkillSkinUseCase(
    private val repository: () -> ISkillSkinRepository,
    private val find: (String) -> CompiledSkill?,
) {
    public fun grant(player: PlayerId, baseSkillId: String, skin: String): Result<Unit, SkinError> {
        skinError(baseSkillId, skin)?.let { return Result.failure(it) }
        repository().grant(player, baseSkillId, skin)
        return Result.success(Unit)
    }

    public fun revoke(player: PlayerId, baseSkillId: String, skin: String): Result<Unit, SkinError> {
        repository().revoke(player, baseSkillId, skin)
        return Result.success(Unit)
    }

    /** [skin] null = back to the base. */
    public fun select(player: PlayerId, baseSkillId: String, skin: String?): Result<Unit, SkinError> {
        if (skin != null) skinError(baseSkillId, skin)?.let { return Result.failure(it) }
        if (!repository().select(player, baseSkillId, skin)) return Result.failure(SkinError.NotOwned(baseSkillId, skin!!))
        return Result.success(Unit)
    }

    private fun skinError(baseSkillId: String, skin: String): SkinError? {
        val variant = find("$baseSkillId@$skin") ?: return SkinError.UnknownVariant(baseSkillId, skin)
        return if (variant.patchKind == "skin") null else SkinError.NotASkin(baseSkillId, skin, variant.patchKind)
    }
}
