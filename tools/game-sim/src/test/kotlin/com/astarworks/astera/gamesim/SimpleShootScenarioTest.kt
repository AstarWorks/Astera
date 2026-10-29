package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.admin.AdminSkillCatalog
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.permission.RoleId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** F2-5 (ADR-0196): RTM SimpleShoot through the real wiring, plus direct skill casting. */
class SimpleShootScenarioTest {

    private data class Seen(val tick: Long, val key: String, val count: Int, val p: JsonObject)

    private fun ScenarioScope.simpleShootCasts(): Int = eventsOf<SkillCast>().count { it.skillId.value == "simple-shoot" }

    private fun particles(trace: Path): List<Seen> =
        Json.parseToJsonElement(Files.readString(trace)).jsonObject["ticks"]!!.jsonArray.flatMap { t ->
            val tick = t.jsonObject["tick"]!!.jsonPrimitive.content.toLong()
            t.jsonObject["particles"]!!.jsonArray.map {
                val o = it.jsonObject
                Seen(tick, o["key"]!!.jsonPrimitive.content, o["count"]!!.jsonPrimitive.content.toInt(), o)
            }
        }

    @Test
    fun `simple-shoot bursts at the muzzle, trails the flight and bursts where it lands`() {
        var castTick = 0L
        val trace = scenario("simple-shoot") {
            val shooter = player("shooter")
            shooter.holds("rtm-blaster")
            shooter.lookAt(Vec3(0.0, groundY, 14.0)) // down at the floor ahead: a block hit
            castTick = sim.scheduler.currentTick()
            shooter.rightClick()
            advance(60)
        }
        val all = particles(trace)

        // Cast: one flame burst of 50 at the muzzle on the cast tick (content/skills/simple-shoot `cast_flame`).
        val burst = all.filter { it.key == "flame" && it.count == 50 }
        assertThat(burst).hasSize(1)
        assertThat(burst.single().tick).isBetween(castTick, castTick + 1)

        // Flight: flame + orange dust trail on several ticks while the beam travels.
        val trail = all.filter { it.key == "flame" && it.count == 1 }
        assertThat(trail.map { it.tick }.toSet().size).isGreaterThanOrEqualTo(3)
        val trailDust = all.filter { it.key == "dust" && it.p["scale"]!!.jsonPrimitive.content.toDouble() == 1.5 }
        assertThat(trailDust).isNotEmpty()

        // Impact at the floor: one burst of 60 flame, 8 lava and the two orange dust discs (sizes 2.5 and 2.0) on one tick.
        val impact = all.filter { it.key == "flame" && it.count == 60 }
        assertThat(impact).hasSize(1)
        val impactTick = impact.single().tick
        assertThat(all.filter { it.key == "lava" && it.count == 8 }.map { it.tick }).containsExactly(impactTick)
        for (size in listOf(2.5, 2.0)) {
            assertThat(all.filter { it.key == "dust" && it.p["scale"]!!.jsonPrimitive.content.toDouble() == size }.map { it.tick })
                .`as`("dust size $size at the impact").containsExactly(impactTick)
        }
        val c = all.first { it.key == "dust" && it.p["scale"]!!.jsonPrimitive.content.toDouble() == 2.0 }.p["color"]!!.jsonObject
        assertThat(listOf("r", "g", "b").map { c[it]!!.jsonPrimitive.content.toInt() }).containsExactly(255, 100, 20)
        // The impact comes after the flight: no trail sample after it.
        assertThat(impactTick).isGreaterThanOrEqualTo(trail.maxOf { it.tick } - 1)
    }

    @Test
    fun `an operator casts any skill directly and force skips the cooldown`() {
        scenario("skill-command") {
            val alice = player("alice")
            val list = sim.command("alice", "skill list", operator = true)
            assertThat(list.succeeded).isTrue()
            assertThat(list.replies.filter { it.first.value == "astera.skill.list.entry" }.map { it.second["id"] })
                .contains("simple-shoot", "flame-burst")

            assertThat(sim.complete("alice", "skill cast sim", operator = true)).contains("simple-shoot")

            val first = sim.command("alice", "skill cast simple-shoot", operator = true)
            assertThat(first.succeeded).`as`(first.replies.toString()).isTrue()
            assertThat(simpleShootCasts()).isEqualTo(1)

            val again = sim.command("alice", "skill cast simple-shoot", operator = true)
            assertThat(again.succeeded).isFalse()
            assertThat(again.replies.map { it.first.value }).contains("astera.admin.panel.skills.err.cooldown")

            val forced = sim.command("alice", "skill cast simple-shoot alice --force", operator = true)
            assertThat(forced.succeeded).`as`(forced.replies.toString()).isTrue()
            assertThat(simpleShootCasts()).isEqualTo(2)

            val unknown = sim.command("alice", "skill cast nope", operator = true)
            assertThat(unknown.succeeded).isFalse()

            // Not an operator: the node is hidden / refused.
            assertThat(sim.command("alice", "skill cast simple-shoot").succeeded).isFalse()
            check(alice.name == "alice")
        }
    }

    @Test
    fun `admin panel skills page casts on click and hands out a working tester on shift click`() {
        scenario("skills-panel") {
            val admin = player("admin")
            val r = sim.services.getOrThrow<IPermissionService>().assign(admin.id, RoleId("admin"))
            check(r is Result.Success)
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val view = checkNotNull(sim.server.view(admin.id))

            router.dispatch(view, GuiIntent(AdminSkillCatalog.INTENT_CAST, mapOf(AdminSkillCatalog.PAYLOAD_SKILL to "simple-shoot")))
            assertThat(simpleShootCasts()).isEqualTo(1)

            router.dispatch(view, GuiIntent(AdminSkillCatalog.INTENT_TESTER, mapOf(AdminSkillCatalog.PAYLOAD_SKILL to "flame-burst")))
            assertThat(view.givenItems).contains("skill-tester-flame-burst")

            advance(80) // let the first shot finish
            sim.hold(admin, "skill-tester-flame-burst")
            val before = eventsOf<SkillCast>().count { it.skillId.value == "flame-burst" }
            admin.rightClick()
            assertThat(eventsOf<SkillCast>().count { it.skillId.value == "flame-burst" }).isEqualTo(before + 1)
        }
    }
}
