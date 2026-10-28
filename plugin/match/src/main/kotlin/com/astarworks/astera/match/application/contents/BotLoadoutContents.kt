package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.match.application.config.BotLoadoutPool
import com.astarworks.astera.match.application.config.BotLoadoutYamlConfig

/**
 * Holder of the single `content/bot-loadouts/loadouts.yaml` document, stored under [KEY]. Reloadable via
 * `/astera reload bot-loadouts`; [current] is `null` when the file is absent (bots then keep what they carry).
 */
public class BotLoadoutContents : YamlDynamicContents<String, BotLoadoutPool, BotLoadoutYamlConfig>(
    categoryName = "bot-loadouts",
    deserializer = BotLoadoutYamlConfig.serializer(),
) {
    override fun toEntry(config: BotLoadoutYamlConfig): Pair<String, BotLoadoutPool> = KEY to config.toPool()

    public fun current(): BotLoadoutPool? = find(KEY)

    public companion object {
        public const val KEY: String = "pool"
    }
}
