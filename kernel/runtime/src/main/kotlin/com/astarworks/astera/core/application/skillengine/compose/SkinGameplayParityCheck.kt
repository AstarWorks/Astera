package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.GameplayProjection

/**
 * Check 14 (engine-gaps-v2 2.5): a `patchKind: skin` variant plays exactly the gameplay of its base. Check 13 reads the patch tree and cannot
 * see what a presentation row does to timing; this one compares the two compiled tables through [GameplayProjection]. A variant that fails
 * does not load, so a skin that changes a hit can never be equipped. `balance` and `mechanic` variants are exempt: they are meant to differ.
 */
internal object SkinGameplayParityCheck {
    /** Null = identical gameplay; otherwise the first difference, named. [base] is the base as it composes with its overlays, as the variant derives from it. */
    fun run(base: CompiledSkill, variant: CompiledSkill, variantId: String): SkillDecodeError.SkinChangesGameplay? {
        val diff = GameplayProjection.of(base).firstDifference(GameplayProjection.of(variant)) ?: return null
        val (_, a, b) = diff
        val where = (a ?: b)?.where ?: "skill"
        val src = (a?.src ?: b?.src)?.let { " ($it)" } ?: ""
        return SkillDecodeError.SkinChangesGameplay(variantId, where + src, a?.text ?: "(absent)", b?.text ?: "(absent)")
    }
}
