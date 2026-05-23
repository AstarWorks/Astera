package com.astarworks.astera.items.domain.refinement

/**
 * Whitelisted sub-stat candidates (Wave 5refine, ADR-0100 §sub_stat_rolls).
 *
 * Genshin 聖遺物 / HSR Relic style: each refined item gets up to
 * [RefinementSpec.subStatCount] independent rolls from this enum. Confined to
 * a typed set (rather than free-form [AttributeKey]) so:
 *
 *   - YAML `candidate_stats:` list parses without registry round-trips
 *   - balance review can audit the full possibility space at a glance
 *   - drop-table / loot RNG can weight each candidate without string-fuzz
 *
 * Adding a new candidate requires a deliberate ADR / wave touch — the small
 * cost is what keeps the厳選 design space legible. New axes (e.g. lifesteal,
 * cooldown_reduction) wait for an ADR before becoming roll candidates.
 *
 * The mapping to the underlying
 * [com.astarworks.astera.core.domain.attribute.AttributeKey] (so the
 * per-instance container can store the roll) lives in [attributeKey].
 */
public enum class SubStatKey(public val yamlKey: String) {
    ATTACK("attack"),
    ATTACK_SPEED("attack_speed"),
    CRIT_CHANCE("crit_chance"),
    CRIT_DAMAGE("crit_dmg"),
    DEFENSE("defense"),
    MAX_HEALTH("max_health"),
    MOVEMENT_SPEED("movement_speed"),
    ;

    /**
     * Namespaced [com.astarworks.astera.core.domain.attribute.AttributeKey]
     * stem under which this sub-stat's rolled total is stored on an
     * [ItemInstance]'s container. The full key is
     * `"item.substat.<yamlKey>"` so namespacing matches the rest of Astera's
     * `item.*` attribute prefix.
     */
    public val containerKey: String get() = "item.substat.$yamlKey"

    public companion object {
        public fun fromYaml(raw: String): SubStatKey? =
            entries.firstOrNull { it.yamlKey == raw.trim().lowercase() }
    }
}
