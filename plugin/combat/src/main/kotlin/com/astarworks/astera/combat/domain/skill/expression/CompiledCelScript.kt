package com.astarworks.astera.combat.domain.skill.expression

import com.astarworks.astera.core.domain.Result

/** Immutable evaluator program. Compilation belongs to content loading, evaluation to the cast. */
public fun interface CompiledCelScript {
    public fun evaluate(context: CelContext): Result<Any, CelError>
}
