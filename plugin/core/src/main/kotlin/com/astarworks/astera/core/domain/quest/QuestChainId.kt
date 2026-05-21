package com.astarworks.astera.core.domain.quest

/**
 * Stable identifier for a [QuestChain] (Wave 5s, ADR-0091).
 *
 * Lowercase kebab-case. Mirrors [com.astarworks.astera.core.domain.advancement.AdvancementId]'s
 * convention so YAML authors don't context-switch.
 *
 * A quest chain is the top-level player-visible unit ("rescue the merchant",
 * "harvest 10 chests"); [QuestStageId] identifies individual steps inside a
 * chain (see [QuestStage]).
 */
@JvmInline
public value class QuestChainId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "QuestChainId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
