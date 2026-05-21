package com.astarworks.astera.combat.application.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root YAML shape for `content/balance/status-effect-interactions.yaml`
 * (Wave 5o, ADR-0081).
 *
 * ```yaml
 * rules:
 *   - id: fire-cancels-frost
 *     when:
 *       incoming: burn
 *       existing: [freeze]
 *     do:
 *       cancel: freeze
 *   - id: ice-immunity-after-thaw
 *     when:
 *       incoming: freeze
 *       existing: [burn]
 *     do:
 *       suppress:
 *         effect: freeze
 *         duration_ticks: 60
 *   - id: lightning-amplifies-wet
 *     when:
 *       incoming: shock
 *       existing: [wet]
 *     do:
 *       amplify:
 *         effect: wet
 *         multiplier: 2.0
 * ```
 *
 * Rules are evaluated in declaration order; the first matching rule wins,
 * and the rest do not run. Missing file or empty `rules:` list is permitted
 * — the loader falls back to "Pass for everything".
 *
 * Per the pre-release posture (CLAUDE.md), this is a fresh schema; there is
 * no dual-decoder for any earlier shape. Tests construct the DTO directly.
 */
@Serializable
public data class StatusEffectInteractionConfig(
    public val rules: List<StatusEffectInteractionRuleConfig> = emptyList(),
)

/**
 * Single rule entry. [trigger] is the predicate (incoming id + required
 * existing ids); [action] is the [StatusInteractionAction] taken when the
 * trigger matches.
 */
@Serializable
public data class StatusEffectInteractionRuleConfig(
    public val id: String,
    @SerialName("when") public val trigger: StatusInteractionTriggerConfig,
    @SerialName("do") public val action: StatusInteractionActionConfig,
)

/**
 * The predicate side of a rule.
 *
 * @property incoming Status effect id the incoming application carries.
 * @property existing Status effect ids that must ALL be present on the
 *   target for the rule to fire. Empty list = "trigger on any incoming
 *   regardless of existing effects".
 */
@Serializable
public data class StatusInteractionTriggerConfig(
    public val incoming: String,
    public val existing: List<String> = emptyList(),
)

/**
 * The action side of a rule — exactly one of [cancel] / [suppress] /
 * [amplify] must be non-null. Multiple-set or all-null is a load-time error
 * surfaced as `IllegalArgumentException` from
 * [StatusEffectInteractionConfig] consumers.
 */
@Serializable
public data class StatusInteractionActionConfig(
    public val cancel: String? = null,
    public val suppress: SuppressActionConfig? = null,
    public val amplify: AmplifyActionConfig? = null,
)

@Serializable
public data class SuppressActionConfig(
    public val effect: String,
    @SerialName("duration_ticks") public val durationTicks: Int,
)

@Serializable
public data class AmplifyActionConfig(
    public val effect: String,
    public val multiplier: Double,
)
