package com.astarworks.astera.hudpreview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    require(args.size == 8) { "trace.json pack.zip vanilla-assets.zip output ticks scale player tab" }
    val trace = TraceFrames(Json.parseToJsonElement(Files.readString(Path.of(args[0]))).jsonObject)
    val ticks = parseTicks(args[4], trace.lastTick)
    val scale = args[5].toInt()
    val player = trace.player(args[6])
    require(args[7] in listOf("true", "false")) { "tab must be true or false" }
    val output = Files.createDirectories(Path.of(args[3]))
    PackAssets(Path.of(args[1]), Path.of(args[2])).use { assets ->
        val renderer = HudFrameRenderer(assets)
        for (tick in ticks) {
            val file = output.resolve("tick-${tick.toString().padStart(6, '0')}-scale-$scale.png")
            check(ImageIO.write(renderer.render(trace, tick, player, scale, args[7].toBooleanStrict()), "png", file.toFile()))
            println(file)
        }
    }
}

internal fun parseTicks(selection: String, last: Long): List<Long> {
    if (selection == "last") return listOf(last)
    val ticks = selection.split(',').flatMap { token ->
        if (".." in token) {
            val ends = token.split("..")
            require(ends.size == 2)
            val from = ends[0].toLong()
            val to = ends[1].toLong()
            require(from <= to && to - from <= 1000) { "Tick ranges must be ascending, at most 1001 frames" }
            (from..to).toList()
        } else listOf(token.toLong())
    }.distinct().sorted()
    require(ticks.isNotEmpty() && ticks.all { it in 0..last }) { "Ticks must be within 0..$last" }
    return ticks
}
