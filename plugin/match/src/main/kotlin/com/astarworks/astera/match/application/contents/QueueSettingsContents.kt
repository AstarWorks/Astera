package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.match.application.config.QueueYamlConfig
import com.astarworks.astera.match.domain.matchmaking.QueueSettings

/**
 * Holder of the single `content/matchmaking/queue.yaml` document (WP8),
 * stored under [KEY]. Reloadable via `/astera reload matchmaking`; [current]
 * falls back to [QueueSettings.DEFAULT] when the file is missing or invalid.
 */
public class QueueSettingsContents : YamlDynamicContents<String, QueueSettings, QueueYamlConfig>(
    categoryName = "matchmaking",
    deserializer = QueueYamlConfig.serializer(),
) {
    override fun toEntry(config: QueueYamlConfig): Pair<String, QueueSettings> = KEY to config.toSettings()

    public fun current(): QueueSettings = find(KEY) ?: QueueSettings.DEFAULT

    public companion object {
        public const val KEY: String = "queue"
    }
}
