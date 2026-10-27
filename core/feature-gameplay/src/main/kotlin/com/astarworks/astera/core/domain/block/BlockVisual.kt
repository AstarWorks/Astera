package com.astarworks.astera.core.domain.block

/** Reserved non-placement note states (ADR-0299). No Minecraft API leaks into content. */
public data class BlockVisual(val base: String, val state: Map<String, String>) {
    init {
        require(base == "note_block") { "visual.base must be note_block" }
        require(state.keys == setOf("instrument", "note", "powered") &&
            state["instrument"] == "harp" && state["powered"] == "false" &&
            state["note"]?.toIntOrNull() in 1..9) {
            "visual.state must reserve instrument=harp,note=1..9,powered=false; note=0 is vanilla placement"
        }
    }
    public val blockData: String get() = "minecraft:$base[" + state.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" } + "]"
}
