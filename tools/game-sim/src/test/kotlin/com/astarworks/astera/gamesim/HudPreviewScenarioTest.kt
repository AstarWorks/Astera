package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.hud.status.CooldownHudGlyphs
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.core.domain.status.StatusEffectId
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files

/** Fixed visual fixtures, produced by real wiring rather than hand-authored preview layouts. */
class HudPreviewScenarioTest {
    @ParameterizedTest
    @ValueSource(strings = ["lobby", "pistol", "burning-regen", "low-hp"])
    fun `write a representative loaded-pack HUD trace`(name: String) {
        val path = scenario("hud-preview-$name") {
            val viewer = player("preview", team = if (name == "lobby") null else "red")
            sim.eventBus.publish(ResourcePackStatusChanged(viewer.id, ResourcePackStatus.LOADED))
            if (name != "lobby") {
                val opponent = player("opponent", team = "blue", at = Vec3(0.0, groundY, 30.0))
                startMatch("carry-tnt", mapOf(viewer to "red", opponent to "blue"))
            }
            advance(40)
            when (name) {
                "lobby" -> {
                    advance(40)
                    assertThat(sim.hud.sidebar(viewer.id)).isNotNull()
                    assertThat(sim.hud.statusLineText(viewer.id)).startsWith("hp-bar:")
                }
                "pistol" -> {
                    viewer.holds("pistol")
                    advance(4)
                    viewer.rightClick()
                    advance(2)
                    assertThat(sim.hud.statusAmmo(viewer.id)).isEqualTo("11/12")
                    val line = checkNotNull(sim.hud.statusLine(viewer.id)).component as HudComponent.FontText
                    assertThat(line.text).contains(CooldownHudGlyphs.cooling(SkillTrigger.RIGHT_CLICK).toString())
                }
                "burning-regen" -> {
                    val registry = sim.services.getOrThrow<IStatusEffectRegistry>()
                    val statuses = sim.services.getOrThrow<IStatusEffectContainer>()
                    for (id in listOf("burn", "regen")) {
                        val spec = checkNotNull(registry.find(StatusEffectId(id)))
                        statuses.apply(StatusEffectInstance(spec, viewer.id, 200, 1, sim.tick))
                    }
                    advance(12)
                    assertThat(SimHudRecorder.describe(checkNotNull(sim.hud.actionBar(viewer.id)).component))
                        .contains("burn", "regen")
                }
                "low-hp" -> {
                    val health = sim.services.getOrThrow<IPlayerHealthGateway>()
                    health.setHealth(viewer.id, 1.0, HealthChangeSource.Other("preview"))
                    advance(4)
                    assertThat(sim.hud.statusLineText(viewer.id)).contains("hp-bar:1/")
                }
            }
        }
        val trace = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val hud = trace.getValue("ticks").jsonArray.flatMap { it.jsonObject.getValue("hud").jsonArray }
            .map { it.jsonObject }.filter { it.getValue("op").jsonPrimitive.content != "clear" }
        assertThat(hud).isNotEmpty()
        assertThat(hud).allSatisfy { assertThat(it["layout"]).isInstanceOf(JsonObject::class.java) }
        val status = hud.last { it.getValue("region").jsonPrimitive.content == "STATUS_LINE" }.getValue("layout").jsonObject
        assertThat(status.getValue("component").jsonObject.getValue("text").jsonPrimitive.content).isNotEmpty()
        assertThat(status.getValue("anchorLeftPx").jsonPrimitive.int).isEqualTo(-91)
    }

    @Test
    fun `trace retains font strings without summary truncation and resolves sidebar colours`() {
        scenario("hud-preview-trace-fidelity") {
            val viewer = player("preview")
            sim.eventBus.publish(ResourcePackStatusChanged(viewer.id, ResourcePackStatus.LOADED))
            advance(40)
            val trace = sim.recorder.toJson().jsonObject
            val events = trace.getValue("ticks").jsonArray.flatMap { it.jsonObject.getValue("hud").jsonArray }.map { it.jsonObject }
            val encoded = events.last { it.getValue("region").jsonPrimitive.content == "STATUS_LINE" }
                .getValue("layout").jsonObject.getValue("component").jsonObject
            val current = checkNotNull(sim.hud.statusLine(viewer.id)).component as HudComponent.FontText
            assertThat(encoded.getValue("text").jsonPrimitive.content).isEqualTo(current.text)
            assertThat(encoded.getValue("widthPx").jsonPrimitive.int).isEqualTo(current.widthPx)
            val sidebar = events.last { it.getValue("region").jsonPrimitive.content == "SIDEBAR" }
                .getValue("layout").jsonObject
            assertThat(sidebar.getValue("title").jsonPrimitive.content).doesNotContain("astera.hud.")
            assertThat(sidebar.getValue("lines").toString()).contains("<")
        }
    }
}
