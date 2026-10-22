package com.astarworks.astera.hudpreview

import kotlinx.serialization.json.*

internal data class LiveLayout(val at: Long, val value: JsonObject)

/** HUD entries are deltas: carry them forward over quiet ticks and remove only the cleared region. */
internal class TraceFrames(private val trace: JsonObject) {
    val ticks = trace.getValue("ticks").jsonArray.map { it.jsonObject }
    val players = trace.getValue("entities").jsonArray.map { it.jsonObject }
        .filter { it.getValue("kind").jsonPrimitive.content == "player" }
    val lastTick = ticks.maxOf { it.getValue("tick").jsonPrimitive.long }

    fun player(selector: String): String = players.firstOrNull {
        selector.isEmpty() || it.getValue("id").jsonPrimitive.content == selector || it.getValue("name").jsonPrimitive.content == selector
    }?.getValue("id")?.jsonPrimitive?.content ?: error("No trace player matches '$selector'")

    fun at(tick: Long, player: String): Map<String, LiveLayout> {
        require(tick in 0..lastTick) { "Tick $tick outside trace 0..$lastTick" }
        val live = mutableMapOf<String, LiveLayout>()
        for (frame in ticks.sortedBy { it.getValue("tick").jsonPrimitive.long }) {
            val at = frame.getValue("tick").jsonPrimitive.long
            if (at > tick) break
            for (element in frame.getValue("hud").jsonArray) {
                val event = element.jsonObject
                if (event.getValue("player").jsonPrimitive.content != player) continue
                val region = event.getValue("region").jsonPrimitive.content
                if (event.getValue("op").jsonPrimitive.content == "clear") live.remove(region)
                else live[region] = LiveLayout(at, event["layout"]?.jsonObject
                    ?: error("Trace lacks structured HUD layout; regenerate it with game-sim"))
            }
        }
        return live
    }
}
