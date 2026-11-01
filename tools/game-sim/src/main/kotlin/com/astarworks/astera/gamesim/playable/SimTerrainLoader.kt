package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.bots.TerrainView
import com.astarworks.astera.core.application.service.arena.ArenaImportContent
import com.astarworks.astera.core.application.service.arena.ArenaStructureContent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.stage.ArenaIslandGenerator
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimWorld
import java.nio.file.Path

/** A stage's generated blocks, placed into a [SimWorld] (the same plan the Paper arena builder pastes). */
class SimTerrain(val stage: Stage, val plan: ArenaBuildPlan, val world: WorldId, private val sim: SimWorld) {
    /** Y below which a player dies (the stage layout's `kill_y`). */
    val killY: Double = stage.layout?.killY?.toDouble() ?: (plan.surfaceY - 48.0)

    private val pad = 48

    fun isOccupied(x: Int, y: Int, z: Int): Boolean = sim.isOccupied(world, x, y, z)

    /** Inside the generated area plus a margin of known air: the bot has "received the chunks" here. */
    fun inBounds(x: Int, z: Int): Boolean =
        x in (plan.minX - pad)..(plan.maxX + pad) && z in (plan.minZ - pad)..(plan.maxZ + pad)

    val view: TerrainView = object : TerrainView {
        override fun isSolid(x: Int, y: Int, z: Int): Boolean? = if (inBounds(x, z)) isOccupied(x, y, z) else null

        override fun knows(x: Double, z: Double): Boolean =
            inBounds(Math.floor(x).toInt(), Math.floor(z).toInt())

        // Same rule as the protocol client's Terrain.floorAt: highest solid block with two free blocks above it.
        override fun floorAt(x: Double, z: Double, feetY: Double, maxStepUp: Int, maxDrop: Int): Double? {
            val bx = Math.floor(x).toInt()
            val bz = Math.floor(z).toInt()
            val top = Math.floor(feetY + 0.05).toInt() + maxStepUp
            val bottom = Math.floor(feetY + 0.05).toInt() - maxDrop
            for (y in top downTo bottom) {
                val solid = isSolid(bx, y - 1, bz) ?: return null
                if (!solid) continue
                if (isSolid(bx, y, bz) == true || isSolid(bx, y + 1, bz) == true) continue
                return y.toDouble()
            }
            return null
        }
    }
}

/** Generates a stage's arena (ADR-0246) and places it block by block into the sim world. */
object SimTerrainLoader {
    fun load(sim: GameSim, stage: Stage, contentRoot: Path = GameSim.defaultContentDir()): SimTerrain {
        val parts = ArenaStructureContent.load(contentRoot)
        val imports = ArenaImportContent.load(contentRoot)
        val plan = ArenaIslandGenerator.generate(stage, parts, imports)
        check(plan.problems.isEmpty()) { "stage ${stage.id.value} has layout problems: ${plan.problems}" }
        val world = stage.world
        plan.blocks.forEach { (pos, state) ->
            val key = state.substringBefore('[').let { if (':' in it) it else "minecraft:$it" }
            sim.world.placeBlock(world, Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()), BlockType(key))
        }
        return SimTerrain(stage, plan, world, sim.world)
    }
}
