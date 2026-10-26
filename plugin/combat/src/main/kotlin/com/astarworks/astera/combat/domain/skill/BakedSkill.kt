package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.combat.domain.skill.expression.CompiledCelScript
import com.astarworks.astera.core.domain.skill.targeter.BakedHitShape

/** Load-owned immutable programs and conservative lifetime cost, including referenced effects/skills. */
public data class BakedSkill(
    public val hitShape: BakedHitShape? = null,
    public val formulas: Map<String, CompiledCelScript> = emptyMap(),
    public val estimatedOps: Long = 0,
)
