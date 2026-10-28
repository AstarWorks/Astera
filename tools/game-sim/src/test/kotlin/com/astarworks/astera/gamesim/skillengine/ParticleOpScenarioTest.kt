package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleAnchor
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleDefOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleOp
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleStyle
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.OpId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * W1-04 acceptance at the decode layer (ADR-0303 2.2, 2.0b). Lowering to rows (A1 examples d, i, j) runs through the
 * W1-20 harness (loadInline / play) once it lands; until then these cases pin the field table and its validation.
 */
class ParticleOpScenarioTest {
    private val ctx = GrammarContext("particle-op-test.yaml")
    private val path = "machine.states.cast.do[0]"

    private fun decodeParticle(raw: Map<String, Any?>): Result<ParticleOp, SkillDecodeError> =
        when (val r = ParticleOpSpec.fields.decode(raw, ctx, path)) {
            is Result.Success -> ParticleOpSpec.decode(OpId("puff"), null, null, r.value, ctx)
            is Result.Failure -> Result.Failure(r.error)
        }

    @Test
    fun dustWithColorAndSizeDecodesWithVanillaDefaults() {
        val r = decodeParticle(mapOf("particle" to "dust", "anchor" to "palm", "color" to "#ff5fa2", "size" to 1.2))
        val op = (r as Result.Success).value
        assertEquals(Value.Literal(1.0), op.count)
        assertEquals(Value.Literal(0.0), op.extra)
        assertEquals(Value.Literal(com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0)), op.offset)
        val dust = assertInstanceOf(ParticleStyle.Dust::class.java, op.style)
        assertEquals(255, dust.color.r)
        assertEquals(95, dust.color.g)
        assertEquals(162, dust.color.b)
        assertEquals(1.2, dust.size, 1e-9)
        assertEquals(ParticleAnchor.Named("palm"), op.anchor)
    }

    @Test
    fun sizeWithoutColorIsRejected() {
        assertTrue(decodeParticle(mapOf("particle" to "dust", "anchor" to "palm", "size" to 1.2)) is Result.Failure)
    }

    @Test
    fun colorWithoutSizeIsRejected() {
        assertTrue(decodeParticle(mapOf("particle" to "dust", "anchor" to "palm", "color" to "#ff5fa2")) is Result.Failure)
    }

    @Test
    fun blockAndColorTogetherAreRejected() {
        val r = decodeParticle(
            mapOf("particle" to "dust", "anchor" to "palm", "block" to "stone", "color" to "#ff5fa2", "size" to 1.0),
        )
        assertTrue(r is Result.Failure)
    }

    @Test
    fun deletedV2FieldsAreRejected() {
        // dir / spread_deg / speed / inherit_velocity / seed / vanilla were deleted (ADR-0303 2.2); no silent drop.
        listOf("dir", "spread_deg", "speed", "inherit_velocity", "seed", "vanilla").forEach { key ->
            val r = decodeParticle(mapOf("particle" to "crit", "anchor" to "muzzle", key to 1))
            assertTrue(r is Result.Failure, "field '$key' must be rejected")
        }
    }

    @Test
    fun fractionalCountIsRejected() {
        assertTrue(decodeParticle(mapOf("particle" to "flame", "anchor" to "muzzle", "count" to 2.5)) is Result.Failure)
    }

    @Test
    fun anchorSweepMapDecodes() {
        val r = decodeParticle(
            mapOf("particle" to "end_rod", "anchor" to mapOf("of" to "bolt", "sweep" to 0.3)),
        )
        val op = (r as Result.Success).value
        assertEquals(ParticleAnchor.Sweep("bolt", 0.3), op.anchor)
    }

    @Test
    fun particleDefDecodesAsPointKind() {
        val raw = mapOf("def" to "frost-cast", "anchor" to "muzzle")
        val values = (ParticleDefOpSpec.fields.decode(raw, ctx, path) as Result.Success).value
        val op = (ParticleDefOpSpec.decode(OpId("frost"), null, null, values, ctx) as Result.Success).value
        assertEquals("frost-cast", op.def)
        assertEquals(ParticleAnchor.Named("muzzle"), op.anchor)
        assertEquals(com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind.POINT, ParticleDefOpSpec.timing)
    }
}
