package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.match.application.config.OreNodeYamlConfig
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry

/** `content/ore-nodes/<stage-id>.yaml`, stored as the DTO keyed by stage id (resolved against the block registry at match start). */
public class OreNodeContents : YamlDynamicContents<String, OreNodeYamlConfig, OreNodeYamlConfig>(
    categoryName = "ore-nodes",
    deserializer = OreNodeYamlConfig.serializer(),
), IOreNodeRegistry {
    override fun toEntry(config: OreNodeYamlConfig): Pair<String, OreNodeYamlConfig> = config.id to config
}
