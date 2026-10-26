package com.astarworks.astera.core.domain.quest

/**
 * Stable identifier for a [QuestStage] within a [QuestChain] (Wave 5s, ADR-0091).
 *
 * Stage ids are **only unique within their parent chain**. The persistence layer
 * stores `(player_id, chain_id, stage_id)` triples and the runtime always
 * resolves a stage relative to a chain — so two different chains may both
 * have a `start` stage without collision.
 *
 * Naming rule: lowercase kebab-case (same grammar as [QuestChainId]).
 */
@JvmInline
public value class QuestStageId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "QuestStageId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
