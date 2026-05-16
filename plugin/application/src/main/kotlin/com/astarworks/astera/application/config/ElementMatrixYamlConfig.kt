package com.astarworks.astera.application.config

import com.astarworks.astera.domain.model.weapon.DamageAttribute
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/balance/element-matrix.yaml`.
 *
 * Schema:
 *
 * ```yaml
 * matchups:
 *   fire:
 *     water: 0.5    # fire attacker vs water defender = 0.5x damage
 *     wind:  1.5
 *   water:
 *     fire:  1.5
 *     ...
 * ```
 *
 * Missing pairs default to `1.0` (neutral) at lookup time — authors do not
 * have to fill every cell of the NxN table.
 *
 * Kept separate from `IElementMatrix` (domain) so the domain stays free of
 * serialization annotations and the YAML schema can evolve independently.
 */
@Serializable
public data class ElementMatrixYamlConfig(
    val matchups: Map<String, Map<String, Double>> = emptyMap(),
) {
    /**
     * Strict parsing: any key that doesn't map to a [DamageAttribute] throws
     * [IllegalArgumentException] so authors get a clear error instead of a
     * silent fallback to `1.0`.
     */
    public fun toTable(): Map<DamageAttribute, Map<DamageAttribute, Double>> =
        matchups.entries.associate { (attackerKey, defenderMap) ->
            val attacker = parseAttribute(attackerKey, role = "attacker")
            val inner = defenderMap.entries.associate { (defenderKey, multiplier) ->
                require(multiplier >= 0.0) {
                    "element-matrix multiplier must be >= 0 (got $multiplier for $attackerKey vs $defenderKey)"
                }
                parseAttribute(defenderKey, role = "defender") to multiplier
            }
            attacker to inner
        }

    private fun parseAttribute(raw: String, role: String): DamageAttribute = try {
        DamageAttribute.valueOf(raw.uppercase())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException(
            "Unknown $role attribute '$raw' in element-matrix.yaml. Valid: ${DamageAttribute.entries.joinToString { it.name.lowercase() }}",
            e,
        )
    }
}
