package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaStructureBox
import com.astarworks.astera.core.domain.stage.ArenaStructurePart
import kotlinx.serialization.Serializable

@Serializable
public data class ArenaStructureYamlConfig(val id: String, val boxes: List<ArenaStructureBoxYamlConfig>) {
    public fun toDomain(): ArenaStructurePart = ArenaStructurePart(id, boxes.map {
        require(it.min.size == 3 && it.max.size == 3) { "structure min/max require [x,y,z]" }
        ArenaStructureBox(ArenaBlockPos(it.min[0], it.min[1], it.min[2]), ArenaBlockPos(it.max[0], it.max[1], it.max[2]), it.block)
    })
}

@Serializable
public data class ArenaStructureBoxYamlConfig(val min: List<Int>, val max: List<Int>, val block: String)
