package com.astarworks.astera.combat.application.service.skin

import com.astarworks.astera.combat.application.port.outbound.skin.ISkillSkinRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill

/**
 * Decides which catalog id a cast of base skill `baseId` plays for a caster (engine-gaps-v2 2.3): the skin the caster owns and selected,
 * else the base. Called on the cast path of every host, so a skin that vanished from the catalog (patch file deleted, a reload rejected by
 * check 14) casts the base and reports [Resolved.missingSkin]: the cast is never dropped for a content problem, and the host logs it.
 *
 * [repository] is read per call so a Persistence overlay registered after Combat enabled still wins; [find] is the live catalog, so a
 * hot-reloaded skin or balance overlay applies to the next cast with no restart.
 */
public class SkillVariantResolver(
    private val repository: () -> ISkillSkinRepository,
    private val find: (String) -> CompiledSkill?,
) {
    /** [skillId] is the catalog id to cast. [missingSkin] is the selected skin id that could not be used (then [skillId] is the base). */
    public data class Resolved(val skillId: String, val missingSkin: String? = null)

    public fun resolve(caster: PlayerId, baseId: String): Resolved {
        // a variant id asked for directly (admin cast of `base@skin`) is played as given
        if ('@' in baseId) return Resolved(baseId)
        val skin = repository().selected(caster, baseId) ?: return Resolved(baseId)
        val variantId = "$baseId@$skin"
        val variant = find(variantId)
        return if (variant != null && variant.patchKind == "skin") Resolved(variantId) else Resolved(baseId, missingSkin = variantId)
    }
}
