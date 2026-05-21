package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.config.StatusEffectInteractionConfig
import com.astarworks.astera.combat.application.config.StatusEffectInteractionRuleConfig
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectInteractionRule
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.interaction.StatusInteraction
import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * In-memory [IStatusEffectInteractionRule] (Wave 5o, ADR-0081).
 *
 * Configuration is supplied at construction (no Bukkit / no file I/O — the
 * caller's `AsteraCombatPlugin.onEnable` reads
 * `content/balance/status-effect-interactions.yaml` via kaml and feeds the
 * deserialized [StatusEffectInteractionConfig] in). This keeps the resolver
 * itself pure-Kotlin and unit-testable without a file system.
 *
 * # Semantics
 *
 *  - Rules are evaluated in declared order; the first matching rule wins
 *    and the rest do not run. Equivalent to vanilla Minecraft's "list of
 *    `if`s, first hit" behaviour, which authors find more predictable than
 *    a priority field.
 *  - A rule matches when (a) [StatusEffectInstance.spec.id] equals the
 *    trigger's `incoming` id, AND (b) every id in `existing` appears in
 *    the target's active effect list.
 *  - Empty `existing` list means "always trigger on this incoming id"
 *    regardless of current effects (useful for global immunities).
 *  - Each [StatusInteractionActionConfig] must set exactly one of
 *    `cancel` / `suppress` / `amplify`; this is checked at construction
 *    and throws `IllegalArgumentException` on misconfiguration so the
 *    failure is visible at plugin enable, not at first hit.
 */
public class YamlStatusEffectInteractionRule(
    config: StatusEffectInteractionConfig,
) : IStatusEffectInteractionRule {

    /**
     * Resolver-ready rules. Pre-validated at construction so [resolve] never
     * inspects the raw YAML shape.
     */
    private val rules: List<CompiledRule> = config.rules.map { it.compile() }

    override fun resolve(
        incoming: StatusEffectInstance,
        existing: Collection<StatusEffectInstance>,
    ): StatusInteraction {
        for (rule in rules) {
            if (rule.incomingId != incoming.spec.id) continue
            if (!rule.requiredExistingIds.all { req -> existing.any { it.spec.id == req } }) continue
            return rule.interaction
        }
        return StatusInteraction.Pass
    }

    /** All rule ids registered, in declaration order. Useful for audit / tests. */
    public fun ruleIds(): List<String> = rules.map { it.id }

    private data class CompiledRule(
        val id: String,
        val incomingId: StatusEffectId,
        val requiredExistingIds: List<StatusEffectId>,
        val interaction: StatusInteraction,
    )

    private companion object {
        fun StatusEffectInteractionRuleConfig.compile(): CompiledRule {
            val interaction = when {
                action.cancel != null && action.suppress == null && action.amplify == null ->
                    StatusInteraction.Cancel(StatusEffectId(action.cancel))
                action.suppress != null && action.cancel == null && action.amplify == null ->
                    StatusInteraction.Suppress(
                        suppressedEffectId = StatusEffectId(action.suppress.effect),
                        durationTicks = action.suppress.durationTicks,
                    )
                action.amplify != null && action.cancel == null && action.suppress == null ->
                    StatusInteraction.Amplify(
                        targetEffectId = StatusEffectId(action.amplify.effect),
                        multiplier = action.amplify.multiplier,
                    )
                else -> throw IllegalArgumentException(
                    "Status interaction rule '$id' must set exactly one of " +
                        "cancel / suppress / amplify (got: cancel=${action.cancel}, " +
                        "suppress=${action.suppress}, amplify=${action.amplify}).",
                )
            }
            return CompiledRule(
                id = id,
                incomingId = StatusEffectId(trigger.incoming),
                requiredExistingIds = trigger.existing.map { StatusEffectId(it) },
                interaction = interaction,
            )
        }
    }
}
