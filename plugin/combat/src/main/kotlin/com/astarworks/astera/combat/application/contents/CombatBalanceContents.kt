package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.config.StatusEffectInteractionLoader
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectInteractionRule
import com.astarworks.astera.combat.application.service.YamlElementMatrix
import com.astarworks.astera.combat.application.service.YamlStatusEffectInteractionRule
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.interaction.StatusInteraction
import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.damage.DamageAttribute
import java.nio.file.Path

/**
 * The combat half of `content/balance/` as one reloadable category
 * (`balance-combat`, F3-9 / ADR-0201): `element-matrix.yaml` (damage
 * multipliers) and `status-effect-interactions.yaml`.
 *
 * [elementMatrix] and [interactionRule] are stable delegating views handed to
 * the service registry once; [reload] parses both files first and swaps both
 * only if both parse (all-or-nothing, like every other category), so a typo
 * leaves the running balance untouched.
 */
public class CombatBalanceContents(
    initialMatrix: IElementMatrix,
    initialRule: IStatusEffectInteractionRule,
) : ReloadableContents {

    @Volatile private var matrix: IElementMatrix = initialMatrix

    @Volatile private var rule: IStatusEffectInteractionRule = initialRule

    override val categoryName: String = CATEGORY

    public val elementMatrix: IElementMatrix = object : IElementMatrix {
        override fun multiplier(attacker: DamageAttribute, defender: DamageAttribute): Double =
            matrix.multiplier(attacker, defender)
    }

    public val interactionRule: IStatusEffectInteractionRule = object : IStatusEffectInteractionRule {
        override fun resolve(
            incoming: StatusEffectInstance,
            existing: Collection<StatusEffectInstance>,
        ): StatusInteraction = rule.resolve(incoming, existing)
    }

    override fun reload(dir: Path): ContentLoader.LoadReport {
        val failures = mutableListOf<ContentLoader.LoadFailure>()
        val matrixFile = dir.resolve(MATRIX_FILE)
        val ruleFile = dir.resolve(INTERACTIONS_FILE)
        val newMatrix = attempt(matrixFile, failures) { YamlElementMatrix.loadStrict(matrixFile) }
        val newRule = attempt(ruleFile, failures) {
            YamlStatusEffectInteractionRule(StatusEffectInteractionLoader.load(ruleFile))
        }
        if (newMatrix == null || newRule == null) return ContentLoader.LoadReport(0, failures.size, failures)
        matrix = newMatrix
        rule = newRule
        return ContentLoader.LoadReport(2, 0)
    }

    private fun <T> attempt(file: Path, failures: MutableList<ContentLoader.LoadFailure>, block: () -> T): T? =
        try {
            block()
        } catch (ex: Exception) {
            failures += ContentLoader.LoadFailure(file, ex.message ?: ex::class.simpleName.orEmpty())
            null
        }

    public companion object {
        public const val CATEGORY: String = "balance-combat"
        public const val MATRIX_FILE: String = "element-matrix.yaml"
        public const val INTERACTIONS_FILE: String = "status-effect-interactions.yaml"
    }
}
