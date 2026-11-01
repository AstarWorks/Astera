package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import java.nio.file.Path

/**
 * `./gradlew :tools:game-sim:massPlay -Pruns=20 -Pseeds=1,2,3` -> build/play/summary.md.
 * Each match is the real bot-vs-bot [MatchDriver]: 3 teams x 3 BotBrain seats on annihilation-arena, one match per seed.
 */
fun main(args: Array<String>) {
    val outDir = Path.of(args.getOrElse(0) { "build/play" })
    val runs = args.getOrNull(1)?.toIntOrNull() ?: 5
    val explicit = args.getOrNull(2)?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
    val seeds = explicit.ifEmpty { (1L..runs).map { 20261022L + it } }
    val results = MassPlay.run(seeds, outDir) { seed -> MatchDriver.boot(MatchConfig(seed = seed)) }
    println(MassPlay.summary(results))
}
