package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.arenapreview.ArenaRenderer
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.service.arena.ArenaImportContent
import com.astarworks.astera.core.application.service.arena.ArenaStructureContent
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.stage.ArenaIslandGenerator
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntityKind
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Top-down PNG of a running match: the stage terrain from `ArenaRenderer.top` (same renderer as `arena-preview`) with
 * players (team colour), pillars (alive = filled, broken = hollow cross) and star drops overlaid. Ore nodes are not
 * drawn: GameSim keeps node positions private (SimOreNodeWorld).
 */
class FrameRenderer(
    private val sim: GameSim,
    private val stageId: StageId,
    private val matchId: MatchId,
    private val contentDir: Path = GameSim.defaultContentDir(),
) {
    private class Base(val image: BufferedImage, val plan: ArenaBuildPlan, val stage: Stage)

    private val base: Base by lazy {
        cache.computeIfAbsent("$contentDir|${stageId.value}") {
            val stage = checkNotNull(sim.services.get<IStageRegistry>()?.find(stageId)) { "stage ${stageId.value} is not registered" }
            val plan = ArenaIslandGenerator.generate(stage, ArenaStructureContent.load(contentDir), ArenaImportContent.load(contentDir))
            Base(ArenaRenderer.top(stage, plan), plan, stage)
        }
    }

    /** Renders the current sim state and writes [dir]/t[tick].png; returns the file. */
    fun render(tick: Long, dir: Path): Path {
        Files.createDirectories(dir)
        val file = dir.resolve("t$tick.png")
        ImageIO.write(draw(tick), "png", file.toFile())
        return file
    }

    fun draw(tick: Long): BufferedImage {
        val b = base
        val image = BufferedImage(b.image.width, b.image.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.drawImage(b.image, 0, 0, null)
        fun px(x: Double) = 24 + ((x - b.plan.minX) * SCALE).toInt()
        fun pz(z: Double) = 48 + ((z - b.plan.minZ) * SCALE).toInt()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

        val targets = sim.services.get<IStructureTargets>()
        for (p in b.stage.pillars) {
            val hp = targets?.hitPoints(AnnihilationPillarTargets.idOf("${matchId.value}-${p.team.value}-${p.tier.ordinalValue}"))
            val alive = hp != null && hp > 0.0
            val x = px(p.centre.x); val z = pz(p.centre.z)
            g.color = teamColor(p.team.value)
            if (alive) g.fillRect(x - 3, z - 3, 7, 7) else { g.drawLine(x - 3, z - 3, x + 3, z + 3); g.drawLine(x - 3, z + 3, x + 3, z - 3) }
        }
        for (drop in sim.starDrops.lying()) {
            g.color = Color.YELLOW
            g.fillOval(px(drop.position.x) - 2, pz(drop.position.z) - 2, 5, 5)
        }
        for (e in sim.world.entities) {
            if (e.kind != SimEntityKind.PLAYER) continue
            val dead = !sim.isAlive(e.id)
            val x = px(e.position.x); val z = pz(e.position.z)
            g.color = Color.BLACK; g.fillOval(x - 5, z - 5, 10, 10)
            g.color = if (dead) Color.GRAY else teamColor(e.team?.value)
            g.fillOval(x - 4, z - 4, 8, 8)
        }
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.drawString("tick $tick (${"%.1f".format(tick / 20.0)} s)", 16, image.height - 10)
        g.dispose()
        return image
    }

    private fun teamColor(team: String?): Color = when (team) {
        "red" -> Color(0xff5050)
        "blue" -> Color(0x5090ff)
        "yellow" -> Color(0xffe040)
        "green" -> Color(0x50e060)
        else -> Color.WHITE
    }

    private companion object {
        const val SCALE = 3
        private val cache = ConcurrentHashMap<String, Base>()
    }
}
