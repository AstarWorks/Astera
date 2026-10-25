package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.ICelEvaluator
import com.astarworks.astera.combat.domain.skill.expression.CelError
import com.astarworks.astera.combat.domain.skill.expression.CelSkillScript
import com.astarworks.astera.combat.domain.skill.expression.CompiledCelScript
import com.astarworks.astera.core.domain.Result

/**
 * Phase 1 stub for [ICelEvaluator]. Always returns
 * `Result.Failure(CelError.NotYetImplemented)`. Skills that reference CEL
 * scripts should fall back to non-CEL behavior; once Phase 6 lands a real
 * evaluator, this is replaced via a higher-priority registration on
 * `services.register<ICelEvaluator>(...)`.
 */
public class StubCelEvaluator : ICelEvaluator {
    // The current language has one terminal: unsupported. Do not turn it into a working expression here.
    override fun compile(script: CelSkillScript): CompiledCelScript =
        CompiledCelScript { Result.Failure(CelError.NotYetImplemented) }
}
