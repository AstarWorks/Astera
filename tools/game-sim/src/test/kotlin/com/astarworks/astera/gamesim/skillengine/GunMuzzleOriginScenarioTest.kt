package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A gun's muzzle flash, its smoke and its bolt start on ONE point, and that point is the muzzle of the held gun: it lies in front of the
 * rig's origin (the grip) by about the muzzle length of the item, and along the aim from the eye. The look the founder rejected had the
 * bullet leave the head while the line came from the hand. Real loader, real player, real `content/skills`, GatewayRecorder; the
 * caster stands at the origin, eye 1.62 up, looking along +z.
 */
class GunMuzzleOriginScenarioTest : SkillEngineScenario() {
    /** skill -> (flash op id, smoke op id or null, has a projectile body named bolt). The shotgun is hitscan and its cone smoke is a cone, not the muzzle. */
    private val guns = mapOf(
        "revolver-shot" to Triple("flash", null, true),
        "pistol-shot" to Triple("flash", "smoke", true),
        "pistol-shot-t2" to Triple("flash", "smoke", true),
        "pistol-shot-t3" to Triple("flash", "smoke", true),
        "pistol-shot-t4" to Triple("flash", "smoke", true),
        "smg-burst" to Triple("flash", null, true),
        "rifle-shot" to Triple("flash", "smoke", true),
        "sniper-shot" to Triple("flash", null, true),
        "shotgun-blast" to Triple("fx", null, false),
    )

    private fun shipped(id: String): CompiledSkill {
        val c = SkillComposer(contentDir.resolve("skills")).composeAll()
        val composed = (c as? Result.Success)?.value ?: error("compose failed: $c")
        val ids = composed.keys.toSet()
        val defs = composed.mapValues { (_, t) ->
            when (val d = decodeTree(id, t.tree, ids)) {
                is Result.Success -> d.value
                is Result.Failure -> error("decode: ${d.error}")
            }
        }
        return when (val r = compileAgainst(defs.getValue(id), defs)) {
            is Result.Success -> r.value
            is Result.Failure -> error("compile: ${r.error}")
        }
    }

    private fun vec(s: String): Vec3 = s.split(",").let { Vec3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }

    private val num = "-?[0-9.]+"
    private val triple = "$num,$num,$num"

    private fun firstAt(rows: List<String>, regex: Regex): Vec3? =
        rows.firstNotNullOfOrNull { regex.find(it)?.groupValues?.get(1) }?.let(::vec)

    @Test
    fun flashSmokeAndBoltShareOneMuzzlePointOnTheGun() {
        val eye = Vec3(0.0, 1.62, 0.0)
        val aim = Vec3(0.0, 0.0, 1.0)
        for ((skill, spec) in guns) {
            val (flashId, smokeId, hasBolt) = spec
            val rec = GatewayRecorder()
            rec.faithfulPose = true
            play(shipped(skill), seed = 1L, events = emptyList(), ticks = 4, rec = rec)
            val rows = rec.oracleRows.filter { it.tick == 0 }.map { "${it.verb} ${it.instance} ${it.payload} ${it.detail}" }

            val flash = firstAt(rows, Regex("""^particle \S+ op=\S*/$flashId type=\S+ at=($triple)""")) ?: error("$skill: no muzzle flash at tick 0: $rows")
            val grip = firstAt(rows, Regex("""^frame \S+ op=\S*/rig pos=($triple)""")) ?: error("$skill: no rig frame at tick 0: $rows")

            if (smokeId != null) {
                val smoke = firstAt(rows, Regex("""^particle \S+ op=\S*/$smokeId type=\S+ at=($triple)""")) ?: error("$skill: no muzzle smoke at tick 0")
                assertThat((smoke - flash).length()).describedAs("$skill smoke vs flash").isLessThanOrEqualTo(0.05)
            }
            if (hasBolt) {
                val bolt = firstAt(rows, Regex("""^frame \S+ op=\S*/bolt pos=($triple)""")) ?: error("$skill: no bolt spawn at tick 0")
                assertThat((bolt - flash).length()).describedAs("$skill bolt $bolt vs flash $flash").isLessThanOrEqualTo(0.05)
            }

            // the muzzle of the held gun: ahead of the grip along the aim, about one muzzle length away (0.3 .. 1.3 blocks)
            val out = flash - grip
            assertThat(out.dot(aim)).describedAs("$skill muzzle is ahead of the grip: $out").isGreaterThan(0.25)
            assertThat(out.length()).describedAs("$skill muzzle distance from the grip: $out").isBetween(0.3, 1.3)

            // along the aim from the eye: forward of the eye and within 30 degrees of the look
            val fromEye = flash - eye
            assertThat(fromEye.dot(aim)).describedAs("$skill muzzle is in front of the eye").isGreaterThan(0.7)
            val angle = Math.toDegrees(Math.acos((fromEye.dot(aim) / fromEye.length()).coerceIn(-1.0, 1.0)))
            assertThat(angle).describedAs("$skill muzzle angle off the aim from the eye").isLessThanOrEqualTo(30.0)
        }
    }
}
