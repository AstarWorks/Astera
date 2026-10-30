package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The shotgun's chip follows the caster's live eye and look ray (no eye-height literal, no fixed eye + 4): a swimming (low-eye) caster chips
 * lower than a standing one, and a caster looking down chips the floor in front of the first block on the ray.
 */
class EyeRayChipScenarioTest : SkillEngineScenario() {
    private fun shotgun(): CompiledSkill {
        val c = SkillComposer(contentDir.resolve("skills")).composeAll()
        val composed = (c as? Result.Success)?.value ?: error("compose failed: $c")
        val ids = composed.keys.toSet()
        val defs = composed.mapValues { (_, t) ->
            when (val d = decodeTree("shotgun-blast", t.tree, ids)) {
                is Result.Success -> d.value
                is Result.Failure -> error("decode: ${d.error}")
            }
        }
        return when (val r = compileAgainst(defs.getValue("shotgun-blast"), defs)) {
            is Result.Success -> r.value
            is Result.Failure -> error("compile: ${r.error}")
        }
    }

    /** Ray-vs-plane world: a wall at z = 3 when looking level, the floor y = 0 when looking down. */
    private fun planes(rec: GatewayRecorder) {
        rec.blockRaycast = { _, from, to ->
            val dz = to.z - from.z
            val dy = to.y - from.y
            val tWall = if (dz > 1e-9) (3.0 - from.z) / dz else Double.MAX_VALUE
            val tFloor = if (dy < -1e-9) (0.0 - from.y) / dy else Double.MAX_VALUE
            val t = minOf(tWall, tFloor)
            if (t in 0.0..1.0) BlockHit(Vec3(from.x + (to.x - from.x) * t, from.y + dy * t, from.z + dz * t), Vec3(0.0, 1.0, 0.0), "minecraft:stone") else null
        }
    }

    private fun struck(eyeHeight: Double, pitch: Double, cells: List<BlockCell>): Set<BlockCell> {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.casterEyeHeight = eyeHeight
        planes(rec)
        cells.forEach { rec.world.setBlock(it, "minecraft:stone"); rec.world.setCover(it) }
        play(shotgun(), seed = 1L, events = emptyList(), ticks = 40, rec = rec, pitchDeg = pitch)
        return rec.world.strikes.map { it.second }.toSet()
    }

    @Test
    fun swimmingCasterChipsLowerThanAStandingCaster() {
        val high = BlockCell(0, 2, 3)
        val standing = struck(1.62, 0.0, listOf(high))
        val swimming = struck(0.4, 0.0, listOf(high))
        assertThat(standing).contains(high)
        assertThat(swimming).doesNotContain(high)
    }

    @Test
    fun lookingDownChipsTheFloorInFront() {
        // feet y 0, eye 1.62, pitch 90 (down): the ray ends on the floor at the caster's feet, not 4 blocks below the eye
        val floor = BlockCell(0, -1, 0)
        val deep = BlockCell(0, -3, 0)
        val hit = struck(1.62, 90.0, listOf(floor, deep))
        assertThat(hit).contains(floor)
        assertThat(hit).doesNotContain(deep)
    }
}
