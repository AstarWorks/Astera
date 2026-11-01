package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import java.nio.file.Path

/**
 * `./gradlew :tools:game-sim:massPlay -Pruns=20 [-Pseed=<base>]` -> build/play/summary.md.
 * Run i (1..runs) uses seed base+i. Without -Pseed the base is the current epoch second, so separate invocations never
 * replay the same matches; the base is printed so a batch can be reproduced with -Pseed.
 * Each match is the real bot-vs-bot [MatchDriver]: 3 teams x 3 BotBrain seats on annihilation-arena.
 */
fun main(args: Array<String>) {
    val outDir = Path.of(args.getOrElse(0) { "build/play" })
    val runs = args.getOrNull(1)?.toIntOrNull() ?: 5
    val explicit = args.getOrNull(2)?.trim().orEmpty()
    val base = if (explicit.isEmpty()) java.time.Instant.now().epochSecond
        else explicit.toLongOrNull() ?: error("-Pseed must be a whole number, got '$explicit'")
    val seeds = (1..runs).map { base + it }
    println("massPlay seed base=$base (${if (explicit.isEmpty()) "fresh, epoch seconds" else "from -Pseed"}); runs use seeds ${seeds.joinToString()}")
    val results = MassPlay.run(seeds, outDir) { seed -> MatchDriver.boot(MatchConfig(seed = seed)) }
    println(MassPlay.summary(results))
}
