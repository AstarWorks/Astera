package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.skill.expression.CelSkillScript
import com.astarworks.astera.combat.domain.skill.expression.CompiledCelScript

/**
 * Outbound port: compile a [CelSkillScript] into a reusable evaluator program.
 *
 * Wave 5f ships only [StubCelEvaluator] (always returns
 * Result.Failure(CelError.NotYetImplemented)). Real eval lands in a Phase 6
 * wave once the CEL library (JCEL / proxima / DIY) is chosen.
 */
public interface ICelEvaluator {
    public fun compile(script: CelSkillScript): CompiledCelScript
}
