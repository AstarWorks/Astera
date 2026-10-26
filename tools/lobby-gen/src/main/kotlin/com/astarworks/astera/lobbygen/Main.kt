package com.astarworks.astera.lobbygen

import com.astarworks.astera.core.application.service.lobby.LobbyWorldContent
import com.astarworks.astera.core.domain.Result
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * `--content-dir <dir>` (default `content`) and `--out <png>` (default
 * `docs/assets/lobby-preview.png`). Prints the plan's NPC stands as YAML-ready
 * lines so the lobby-npcs content files can be kept in sync.
 */
fun main(args: Array<String>) {
    var contentDir = Path.of("content")
    var out = Path.of("docs/assets/lobby-preview.png")
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--content-dir" -> contentDir = Path.of(args[++i])
            "--out" -> out = Path.of(args[++i])
            else -> {
                System.err.println("unknown argument ${args[i]}")
                exitProcess(2)
            }
        }
        i++
    }
    val config = when (val r = LobbyWorldContent.load(contentDir)) {
        is Result.Success -> r.value
        is Result.Failure -> {
            System.err.println("lobby.yaml: ${r.error}")
            exitProcess(1)
        }
    }
    val queueYaml = contentDir.resolve("matchmaking/queue.yaml")
    val lanes = if (Files.isRegularFile(queueYaml)) {
        Regex("""(?m)^\s*-\s*id:\s*(\S+)""").findAll(Files.readString(queueYaml)).map { it.groupValues[1] }.toList()
    } else {
        emptyList()
    }
    val plan = LobbyWorldContent.plan(config, lanes)
    println("blocks=${plan.blocks.size} bounds=x[${plan.minX},${plan.maxX}] y[${plan.minY},${plan.maxY}] z[${plan.minZ},${plan.maxZ}]")
    println("spawn=${plan.spawn} yaw=${plan.spawnYaw}")
    plan.npcSpots.toSortedMap().forEach { (id, s) -> println("npc $id: x=${s.x} y=${s.y} z=${s.z} yaw=${s.yaw} walkable=${plan.isWalkable(com.astarworks.astera.core.domain.geometry.Vec3(s.x, s.y, s.z))}") }
    val unsupported = plan.training.dummies.filter { !plan.isWalkable(com.astarworks.astera.core.domain.geometry.Vec3(it.x, it.y, it.z)) }
    plan.training.dummies.forEach { println("dummy ${it.id}: x=${it.x} y=${it.y} z=${it.z} deck=${it !in unsupported}") }
    plan.lanePads.forEach { (lane, p) -> println("lane $lane: $p") }
    if (unsupported.isNotEmpty()) {
        System.err.println("training dummies without deck under them: ${unsupported.map { it.id }}")
        exitProcess(3)
    }
    Files.createDirectories(out.toAbsolutePath().parent)
    ImageIO.write(LobbyRenderer.render(plan), "png", out.toFile())
    println("wrote $out")
}
