package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.application.skillengine.runtime.CastEvent
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.CastObserver
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineGateways
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineKit
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.modifier.ModOp
import com.astarworks.astera.core.domain.skillengine.modifier.ModifierStack
import com.astarworks.astera.core.domain.skillengine.modifier.ParamModifier
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.math.abs

/**
 * The proof of skill engine v2 (ADR-0299, SKL-08): coin 2 of couture-rapier-s2 run by the real cast runner.
 *
 * Runs on the real frame tree, motion laws, contact resolver, decoder / compiler / catalog, CEL layer, op executors
 * (assembled by `SkillEngineKit`) and the shipped coin-2 content; only the ports are fakes. Writes
 * `build/sim-traces/v2/<skill-id>.json` (`astera-sim-trace/2`, see `tools/vfx-shot/SIM-TRACE-V2.md`).
 */
class Coin2CastScenarioTest {

    private val baseId = "couture-rapier-s2"
    private val skinId = "couture-rapier-s2@crimson"
    private val piecesNames = listOf("body", "blade_a", "blade_b")

    private fun catalog(c: Coin2Collaborators): SkillCatalog {
        val catalog = c.newCatalog()
        val report = SkillEngineContents(catalog).reload(c.skillsDir)
        assertThat(report.failed).describedAs("shipped coin-2 content loads: ${report.failures}").isZero()
        assertThat(catalog.ids()).contains(baseId, skinId)
        return catalog
    }

    private fun wired(): Coin2Collaborators = Coin2CastWiring.collaborators()

    private fun events(snaps: List<Snapshot>): List<CastEvent> = snaps.flatMap { it.events }

    private fun contactsOf(snaps: List<Snapshot>): List<ContactEvent> = events(snaps).mapNotNull { it.contact }

    // -------------------------------------------------------------- scenarios

    @Test
    fun `coin 2 plays release hit stick reel arrive with the pieces carried across`() {
        val c = wired()
        val cat = catalog(c)
        val sim = Sim(c, cat, withDummy = true)
        val skill = cat.get(baseId)!!
        val cast = sim.start(skill, 0)
        val end = sim.run(0, listOf(cast))
        val snaps = sim.snapshots(cast)

        val firstSeen = LinkedHashMap<String, Int>()
        for (e in events(snaps)) firstSeen.putIfAbsent(e.name, e.tick)
        val order = listOf("release", "hit", "reel", "arrive")
        for (n in order) assertThat(firstSeen).describedAs("event $n raised: $firstSeen").containsKey(n)
        assertThat(order.map { firstSeen.getValue(it) }).describedAs("event ticks of $order").isSorted()
        // "stick" is the stick op in the hit handler (not an event): the volley hangs off the victim until the reel
        val stuckParent = snaps.filter { it.tick > firstSeen.getValue("hit") && it.tick < firstSeen.getValue("reel") }
            .mapNotNull { it.parents["volley"] }
        assertThat(stuckParent).describedAs("volley is stuck to a contact/enemy frame between hit and reel").isNotEmpty()
        assertThat(stuckParent.last()).describedAs("stuck parents $stuckParent").isEqualTo("contact")
        assertThat(sim.runner.diagnostics()).describedAs("no op failed").isEmpty()

        // damage 18 on the throw, bleed 2 stacks
        assertThat(sim.gameplay.damage.first().amount).isEqualTo(18.0)
        assertThat(sim.gameplay.statuses.first().first).isEqualTo("bleed")
        assertThat(sim.gameplay.statuses.first().second).isEqualTo(2)

        // the 3 pieces ride the volley frame from release on, without a position jump
        val release = firstSeen.getValue("release")
        val at = snaps.associateBy { it.tick }
        val before = at.getValue(release - 1)
        val during = at.getValue(release)
        val after = at.getValue(release + 1)
        for (piece in piecesNames) {
            assertThat(during.parents[piece]).describedAs("$piece parent after release").isEqualTo("volley")
            val stepIn = during.origins.getValue(piece).distance(before.origins.getValue(piece))
            val stepNext = after.origins.getValue(piece).distance(during.origins.getValue(piece))
            assertThat(abs(stepIn - stepNext)).describedAs("$piece displacement on the release tick $stepIn vs next $stepNext").isLessThan(0.75)
        }

        // back on the shears at the end
        val last = snaps.last()
        for (piece in piecesNames) assertThat(last.parents[piece]).describedAs("$piece parent at the end").isEqualTo("shears")

        writeTrace(skill, sim, cast, end)
    }

    @Test
    fun `a skin patch changes colours only, contacts are identical`() {
        val c = wired()
        val cat = catalog(c)
        val sim = Sim(c, cat, withDummy = true)
        val base = sim.start(cat.get(baseId)!!, 0)
        val skin = sim.start(cat.get(skinId)!!, 0)
        val end = sim.run(0, listOf(base, skin))
        val a = contactsOf(sim.snapshots(base))
        val b = contactsOf(sim.snapshots(skin))
        assertThat(a).describedAs("base has contacts").isNotEmpty()
        assertThat(b).describedAs("skin contacts equal base contacts").isEqualTo(a)
        writeTrace(cat.get(skinId)!!, sim, skin, end)
    }

    @Test
    fun `range modifier of one half shortens the fly and the thread`() {
        val c = wired()
        val cat = catalog(c)
        val sim = Sim(c, cat, withDummy = false)
        val half = ModifierStack(listOf(ParamModifier("test", "range", ModOp.MULTIPLY, 0.5)))
        val full = sim.start(cat.get(baseId)!!, 0)
        val short = sim.start(cat.get(baseId)!!, 0, half)
        sim.run(0, listOf(full, short))

        fun reach(cast: CastId): Double = sim.snapshots(cast).maxOf { s ->
            val volley = s.origins["volley"]
            val palm = s.origins["palm"]
            if (volley == null || palm == null) 0.0 else volley.distance(palm)
        }
        val fullReach = reach(full)
        val shortReach = reach(short)
        assertThat(fullReach).describedAs("full fly/thread length").isGreaterThan(3.0)
        assertThat(shortReach).describedAs("half-range fly/thread length vs full $fullReach").isLessThan(fullReach * 0.8)
    }

    @Test
    fun `per skill reload changes the next cast only`(@TempDir tmp: Path) {
        val c = wired()
        val dir = tmp.resolve("skills")
        Files.createDirectories(dir)
        Files.list(c.skillsDir).use { s ->
            s.filter { it.fileName.toString().startsWith(baseId) }.forEach { src ->
                src.toFile().copyRecursively(dir.resolve(src.fileName.toString()).toFile(), overwrite = true)
            }
        }
        val catalog = c.newCatalog()
        val contents = SkillEngineContents(catalog)
        assertThat(contents.reload(dir).failed).isZero()

        val sim = Sim(c, catalog, withDummy = false)
        val first = sim.start(catalog.get(baseId)!!, 0)
        var t = 0
        while (t < 14) sim.tickOnce(t++)

        // edit the flight speed on disk and reload: only the coin-2 ids are recomposed
        val file = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) && it.toString().contains("throw_speed") || Files.isRegularFile(it) && Files.readString(it).contains("throw_speed") }.findFirst().get() }
        val text = Files.readString(file)
        val edited = text.replace(Regex("throw_speed:\\s*1\\.7"), "throw_speed: 1.2")
        assertThat(edited).describedAs("coin-2 file declares throw_speed: 1.7").isNotEqualTo(text)
        Files.writeString(file, edited)
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        val report = contents.reload(dir)
        assertThat(report.failed).isZero()
        assertThat(report.loaded).describedAs("only the coin-2 skills were reloaded").isBetween(1, catalog.ids().size)

        val second = sim.start(catalog.get(baseId)!!, t)
        sim.run(t, listOf(first, second))

        fun flightSpeed(cast: CastId): Double {
            val snaps = sim.snapshots(cast)
            val release = events(snaps).first { it.name == "release" }.tick
            val at = snaps.associateBy { it.tick }
            return at.getValue(release + 3).origins.getValue("volley").distance(at.getValue(release + 2).origins.getValue("volley"))
        }
        assertThat(flightSpeed(first)).describedAs("cast in progress keeps its definition").isEqualTo(1.7, org.assertj.core.data.Offset.offset(0.05))
        assertThat(flightSpeed(second)).describedAs("next cast uses the reloaded one").isEqualTo(1.2, org.assertj.core.data.Offset.offset(0.05))
    }
}
