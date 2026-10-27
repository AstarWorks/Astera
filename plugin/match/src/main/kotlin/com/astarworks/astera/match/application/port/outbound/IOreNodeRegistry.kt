package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.match.application.config.OreNodeYamlConfig

/** Authored Mid ore node templates by stage id (`content/ore-nodes/`). */
public interface IOreNodeRegistry : Contents<String, OreNodeYamlConfig>
