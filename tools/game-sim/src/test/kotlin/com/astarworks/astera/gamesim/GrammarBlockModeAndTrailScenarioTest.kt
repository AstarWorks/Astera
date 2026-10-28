package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.RibbonMesh
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * SKL-15 rows 10 and 11: a projectile's `block_mode` (despawn / stick / bounce / pass) and the multi-band `trail`
 * (bands, edge, dark_rim, step_deg, lag_ticks, double_sided), through the real decoders, executors and runner.
 */
class GrammarBlockModeAndTrailScenarioTest {

    private fun skills(dir: Path, vararg yaml: Pair<String, String>): Path {
        for ((id, body) in yaml) {
            val d = Files.createDirectories(dir.resolve(id))
            Files.writeString(d.resolve("skill.yaml"), body)
        }
        return dir
    }

    private fun shot(id: String, projectileExtra: String, gravity: Double = 0.0, velocity: String = "[0.0, 0.0, 1.0]") = id to """
apiVersion: "astera/v2"
kind: Skill
id: $id
meta: {name: {ja: "x", en: "x"}, description: {ja: "x", en: "x"}, cooldown_ticks: 1, resource_cost: 0, tags: [ranged]}
frames:
  - {id: bolt, kind: body, parent: world, inactive: true}
  - {id: muzzle, kind: point, parent: caster.eye, offset: [0.0, 0.0, 0.5]}
gameplay:
  timeline:
    - {id: t0, at: 0, do: [{id: bolt, op: projectile, body: bolt, from: muzzle, velocity: $velocity, gravity: $gravity, max_ticks: 60, radius: 0.2, $projectileExtra}]}
  "on":
    block:
      - {op: break_area, shape: sphere, radius: 0.5, block_damage: 1, breaks: true, length: 0.0, id: chip, at: "= contact.point"}
"""

    private fun sim(dir: Path, wall: Double? = 6.0, floor: Double? = null) =
        CastSim(emptyMap(), blockAtZ = wall, floorY = floor, skillsDir = dir)

    @Test
    fun `despawn is the default and ends at the face with one handler run`(@TempDir tmp: Path) {
        val dir = skills(tmp, shot("m-despawn", ""), shot("m-despawn2", "block_mode: despawn"))
        for (id in listOf("m-despawn", "m-despawn2")) {
            val s = sim(dir)
            s.cast(id, 30)
            assertThat(s.blockStrikes).describedAs("$id handler count").hasSize(1)
            assertThat(s.trace("bolt").values.last().z).isCloseTo(6.0, org.assertj.core.data.Offset.offset(1e-6))
        }
    }

    @Test
    fun `stick stays embedded at the contact point for stick_ticks and then despawns`(@TempDir tmp: Path) {
        val dir = skills(tmp, shot("m-stick", "block_mode: stick, stick_ticks: 10"))
        val s = sim(dir)
        s.cast("m-stick", 40)
        assertThat(s.blockStrikes).hasSize(1)
        val t = s.trace("bolt")
        val hit = t.entries.first { it.value.z >= 6.0 - 1e-6 }.key
        for (k in hit..hit + 8) assertThat(t[k]!!.z).describedAs("embedded at tick $k").isCloseTo(6.0, org.assertj.core.data.Offset.offset(1e-6))
        assertThat(t.keys.max()).describedAs("gone after stick_ticks").isLessThanOrEqualTo(hit + 11)
        assertThat(t.keys.max()).isGreaterThanOrEqualTo(hit + 9)
    }

    private fun shotWithMesh(id: String, projectileExtra: String) = shot(id, projectileExtra).let { (i, y) ->
        i to y + """
presentation:
  timeline:
    - {id: fx0, at: 0, do: [{id: mesh, op: mesh, frame: bolt, item: "minecraft:prismarine_shard", scale: 0.9}]}
"""
    }

    @Test
    fun `a stuck projectile keeps its mesh until stick_end while despawn removes it at the face`(@TempDir tmp: Path) {
        val dir = skills(tmp, shotWithMesh("m-stick-mesh", "block_mode: stick, stick_ticks: 10"), shotWithMesh("m-despawn-mesh", "block_mode: despawn"))
        for ((id, sticks) in listOf("m-stick-mesh" to true, "m-despawn-mesh" to false)) {
            lateinit var s: CastSim
            val rec = LeakTrackingVisuals { s.now }
            s = CastSim(emptyMap(), blockAtZ = 6.0, skillsDir = dir, visualGateway = rec)
            s.cast(id, 60)
            val mesh = rec.kind.filterValues { it.startsWith("mesh") }.keys.single()
            val gone = rec.removedAt[mesh]
            assertThat(gone).describedAs("$id mesh removed").isNotNull()
            val hit = s.trace("bolt").entries.first { it.value.z >= 6.0 - 1e-6 }.key
            if (sticks) assertThat(gone!!).describedAs("$id mesh stays embedded until stick_end").isGreaterThanOrEqualTo(hit + 10)
            else assertThat(gone!!).describedAs("$id mesh goes on the contact tick").isLessThanOrEqualTo(hit + 1)
            assertThat(rec.live).describedAs("$id nothing live afterwards").isEmpty()
        }
    }

    @Test
    fun `pass ignores the wall completely`(@TempDir tmp: Path) {
        val dir = skills(tmp, shot("m-pass", "block_mode: pass"))
        val s = sim(dir)
        s.cast("m-pass", 30)
        assertThat(s.blockStrikes).describedAs("no block event").isEmpty()
        assertThat(s.trace("bolt").values.last().z).describedAs("flew through").isGreaterThan(20.0)
    }

    @Test
    fun `bounce off a wall reflects the velocity scaled by restitution`(@TempDir tmp: Path) {
        val dir = skills(tmp, shot("m-bounce", "block_mode: bounce, restitution: 0.5, max_bounces: 2"))
        val s = sim(dir)
        s.cast("m-bounce", 30)
        assertThat(s.blockStrikes).describedAs("one contact with the wall").hasSize(1)
        val t = s.trace("bolt")
        val hit = t.entries.maxBy { it.value.z }.key
        val dz = t[hit + 3]!!.z - t[hit + 2]!!.z
        assertThat(dz).describedAs("reflected, half speed").isCloseTo(-0.5, org.assertj.core.data.Offset.offset(1e-6))
        assertThat(t.values.last().z).describedAs("flying back toward the caster").isLessThan(6.0)
    }

    @Test
    fun `bounce on a floor stops after max_bounces and despawns at the last contact`(@TempDir tmp: Path) {
        val dir = skills(tmp, shot("m-floor", "block_mode: bounce, restitution: 0.6, max_bounces: 3", gravity = 0.08, velocity = "[0.0, 0.0, 0.2]"))
        val s = CastSim(emptyMap(), floorY = 0.0, skillsDir = dir)
        s.cast("m-floor", 80)
        assertThat(s.blockStrikes).describedAs("3 bounces + the final contact").hasSize(4)
        assertThat(s.trace("bolt").values.minOf { it.y }).describedAs("never below the floor").isGreaterThanOrEqualTo(-1e-6)
    }

    // ------------------------------------------------------------------------------------------ trail

    private fun trailSkill(id: String, trailExtra: String) = id to """
apiVersion: "astera/v2"
kind: Skill
id: $id
meta: {name: {ja: "x", en: "x"}, description: {ja: "x", en: "x"}, cooldown_ticks: 1, resource_cost: 0, tags: [ranged]}
frames:
  - {id: bolt, kind: body, parent: world, inactive: true}
  - {id: muzzle, kind: point, parent: caster.eye, offset: [0.0, 0.0, 0.5]}
  - {id: tip, kind: point, parent: bolt, offset: [0.0, 1.0, 0.0]}
  - {id: root, kind: point, parent: bolt, offset: [0.0, 0.0, 0.0]}
gameplay:
  timeline:
    - {id: t0, at: 0, do: [{id: bolt, op: projectile, body: bolt, from: muzzle, velocity: [0.0, 0.0, 1.0], max_ticks: 12, radius: 0.2}]}
presentation:
  timeline:
    - {id: p0, at: 0, do: [{id: ribbon, op: trail, of: tip, root: root, ticks: 6, $trailExtra}]}
"""

    @Test
    fun `a banded trail reaches the gateway with its band count colours edge and rim`(@TempDir tmp: Path) {
        val dir = skills(
            tmp,
            trailSkill(
                "t-bands",
                """bands: [{from: 0.0, to: 0.5, color: "#112233", alpha: 0.2}, {from: 0.5, to: 0.8, color: "#445566", alpha: 0.5, color_to: "#000000"}, {from: 0.8, to: 1.0, color: "#ffffff", alpha: 0.9}], edge: {color: "#fefefe", width: 0.05}, dark_rim: {color: "#201010", alpha: 0.6, width: 0.2}""",
            ),
        )
        var tick = 0
        val rec = RecordingVisuals(now = { tick })
        val s = CastSim(emptyMap(), visualGateway = rec, skillsDir = dir)
        val id = s.start("t-bands")
        while (s.now < 10) { tick = s.now; s.step(s.now + 1) }
        assertThat(id).isNotNull
        val (bands, edge, rim) = rec.trailShapes.values.single()
        assertThat(bands.map { it.color }).containsExactly("#112233", "#445566", "#ffffff")
        assertThat(bands[1].colorTo).isEqualTo("#000000")
        assertThat(edge!!.color).isEqualTo("#fefefe")
        assertThat(rim!!.alpha).isEqualTo(0.6)
        val firstTick = rec.ribbonQuads.minOf { it.tick }
        val oneStep = rec.ribbonQuads.filter { it.tick == firstTick }
        assertThat(oneStep.filter { it.kind == RibbonMesh.PieceKind.BAND }.map { it.colorHex }).containsExactly("#112233", "#445566", "#ffffff")
        assertThat(oneStep.count { it.kind == RibbonMesh.PieceKind.EDGE }).isEqualTo(1)
        assertThat(oneStep.count { it.kind == RibbonMesh.PieceKind.RIM }).isEqualTo(1)
    }

    @Test
    fun `double sided doubles the soft quads and lag delays the ribbon`(@TempDir tmp: Path) {
        val dir = skills(
            tmp,
            trailSkill("t-plain", "bands: [{from: 0.0, to: 1.0, color: \"#ffffff\"}]"),
            trailSkill("t-double", "bands: [{from: 0.0, to: 1.0, color: \"#ffffff\"}], double_sided: true"),
            trailSkill("t-lag", "bands: [{from: 0.0, to: 1.0, color: \"#ffffff\"}], lag_ticks: 3"),
        )
        fun run(id: String): RecordingVisuals {
            var tick = 0
            val rec = RecordingVisuals(now = { tick })
            val s = CastSim(emptyMap(), visualGateway = rec, skillsDir = dir)
            s.start(id)
            while (s.now < 10) { tick = s.now; s.step(s.now + 1) }
            return rec
        }
        val plain = run("t-plain")
        val double = run("t-double")
        val lag = run("t-lag")
        assertThat(double.ribbonQuads.size).isEqualTo(plain.ribbonQuads.size * 2)
        assertThat(double.ribbonQuads.count { it.backFace }).isEqualTo(plain.ribbonQuads.size)
        assertThat(lag.ribbonQuads.minOf { it.tick }).isEqualTo(plain.ribbonQuads.minOf { it.tick } + 3)
    }
}
