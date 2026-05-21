package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.quest.QuestYamlConfig
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.domain.quest.QuestChain
import com.astarworks.astera.core.domain.quest.QuestChainId

/**
 * Quest-side `Contents<QuestChainId, QuestChain>` (Wave 5s, ADR-0091,
 * ADR-0060 substrate).
 *
 * Bootstrap directory: `content/quests/`. Inherits the substrate's atomic
 * map + reload semantics — see [AdvancementContents] for the canonical shape
 * (Wave 5p).
 *
 * Implements [IQuestChainRegistry] (the marker port the use cases consume)
 * so `services.register<IQuestChainRegistry>(questContents, priority = 0)`
 * in `AsteraCorePlugin` exposes this instance to the rest of the codebase.
 */
public class QuestChainContents : YamlDynamicContents<QuestChainId, QuestChain, QuestYamlConfig>(
    categoryName = "quests",
    deserializer = QuestYamlConfig.serializer(),
),
    IQuestChainRegistry {

    override fun toEntry(config: QuestYamlConfig): Pair<QuestChainId, QuestChain> {
        val chain = config.toDomain()
        return chain.id to chain
    }

    /** Convenience overload: register by chain alone (it owns its id). */
    public fun register(chain: QuestChain) {
        register(chain.id, chain)
    }
}
