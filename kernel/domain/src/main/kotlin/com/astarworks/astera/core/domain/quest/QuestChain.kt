package com.astarworks.astera.core.domain.quest

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * A multi-stage progression chain (Wave 5s, ADR-0091).
 *
 * # Why "chain" not "quest"
 *
 * Wave 5p already shipped Norma (single-stage match-bounded objectives) and
 * Custom Advancement (cross-session counter-based unlock). The gap was
 * **multi-stage** progression where each stage gates on different criteria
 * and the player follows a linear or branching narrative. "Chain" captures
 * that the stages are sequenced (not a flat checklist).
 *
 * # Tree shape
 *
 * Stages form a tree via [QuestStage.followingStageId] (parent → many children
 * are legal; the YAML loader does not enforce a single-child tree — a stage
 * may auto-start one of N children, but Phase 1 only chains linearly).
 *
 * The first stage in [stages] is the **entry stage** — `IQuestService.startChain`
 * activates this stage when the player accepts the chain.
 *
 * # Lifecycle
 *
 *  1. Loader: YAML in `content/quests/` parsed into [QuestChain] via
 *     [com.astarworks.astera.core.application.config.quest.QuestYamlConfig].
 *  2. Registry: [com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry]
 *     (Contents-backed) holds the catalog and is reloadable via
 *     `/astera reload quests`.
 *  3. Start: player triggers (NPC interaction, command) call
 *     `IQuestService.startChain` which writes a [PlayerQuestProgress] row
 *     pointing at the first stage and publishes
 *     [com.astarworks.astera.core.domain.event.QuestChainStarted].
 *  4. Progress: per-event listeners (TBD Wave 5pve/6a) call
 *     `IQuestService.progressCurrent` which increments the current stage's
 *     counter; when the counter reaches the stage's criteria target, the
 *     stage rewards are dispatched, [com.astarworks.astera.core.domain.event.QuestStageCompleted]
 *     fires, and the player's row advances to the [QuestStage.followingStageId].
 *  5. Completion: when a stage has `followingStageId == null` and completes,
 *     [com.astarworks.astera.core.domain.event.QuestChainCompleted] fires and
 *     the player's row is marked complete.
 *
 * @property id Stable identifier for the chain.
 * @property titleKey i18n key for the chain's display title.
 * @property stages Ordered list of stages; first entry is the entry stage.
 *  `followingStageId` between stages need not match the list order — the
 *  list is just a container.
 */
public data class QuestChain(
    public val id: QuestChainId,
    public val titleKey: MessageKey,
    public val stages: List<QuestStage>,
) {
    init {
        require(stages.isNotEmpty()) { "QuestChain '${id}' must have at least one stage" }
        val ids = stages.map { it.id }
        require(ids.toSet().size == ids.size) {
            "QuestChain '${id}' has duplicate stage ids: $ids"
        }
        val knownIds = ids.toSet()
        stages.forEach { stage ->
            stage.followingStageId?.let { next ->
                require(next in knownIds) {
                    "QuestChain '${id}' stage '${stage.id}' follows unknown stage '$next'"
                }
            }
        }
    }

    /** The entry stage — what `startChain` activates first. */
    public val entryStage: QuestStage get() = stages.first()

    /** Look up a stage by id; returns `null` if not in this chain. */
    public fun stage(id: QuestStageId): QuestStage? = stages.firstOrNull { it.id == id }
}
