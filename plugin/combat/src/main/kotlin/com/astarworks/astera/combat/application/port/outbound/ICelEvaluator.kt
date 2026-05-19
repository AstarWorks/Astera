package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.skill.expression.CelContext
import com.astarworks.astera.combat.domain.skill.expression.CelError
import com.astarworks.astera.combat.domain.skill.expression.CelSkillScript
import com.astarworks.astera.core.domain.Result

/**
 * Outbound port: evaluate a [CelSkillScript] in a [CelContext].
 *
 * Wave 5f ships only [StubCelEvaluator] (always returns
 * Result.Failure(CelError.NotYetImplemented)). Real eval lands in a Phase 6
 * wave once the CEL library (JCEL / proxima / DIY) is chosen.
 */
public interface ICelEvaluator {
    public fun evaluate(script: CelSkillScript, context: CelContext): Result<Any, CelError>
}
