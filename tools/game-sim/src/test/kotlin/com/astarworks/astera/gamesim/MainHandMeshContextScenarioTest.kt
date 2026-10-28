package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.geometry.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SKL-14 R8: a `main_hand` mesh is v1's AnchorMainHand (the caster's real stack in THIRDPERSON_RIGHTHAND,
 * v1 PaperDisplayEntityGateway.kt:192-199), never a rebuilt stack drawn in FIXED.
 */
class MainHandMeshContextScenarioTest {
    @Test
    fun `main_hand mesh spawns as the anchor main hand weapon`() {
        val origin = Vec3(37.5, -60.0, -12.5)
        val dummies = mapOf(simVictim(1) to ahead(origin, 0.0, 3.0))
        lateinit var sim: CastSim
        val rec = RecordingVisuals(now = { sim.now })
        sim = CastSim(dummies, origin = origin, visualGateway = rec)
        sim.cast("bastion-greatsword-s1", 60)
        val kinds = rec.specs.values.map { it.kind }
        assertTrue(kinds.any { it == DisplayKind.AnchorMainHand }, "no AnchorMainHand display; kinds=${kinds.map { it::class.simpleName }.distinct()}")
    }
}
