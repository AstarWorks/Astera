package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Real shipped v3 content -> compiler -> table player -> visual port, including lifetime cleanup. */
class ShippedShaderDisplayScenarioTest : SkillEngineScenario() {
    @Test
    fun shippedShaderDemosPlayWithTheirShaderSelection() {
        val demos = (SkillComposer(contentDir.resolve("vfx")).composeAll() as Result.Success).value
        assertThat(demos).hasSize(3)
        for ((id, tree) in demos) {
            val skill = (compileTree(id, tree.tree) as Result.Success).value
            val rec = GatewayRecorder()
            val effects = mutableListOf<String?>()
            val visual = object : ISkillVisualGateway by rec.visual {
                override fun spawnMesh(cast: CastRef, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook, view: DisplayView, glow: String?) =
                    rec.visual.spawnMesh(cast, item, part, pose, brightness, look, view, glow).also { effects += look.shaderEffect }
            }
            val player = playProbe(skill, ticks = 102, rec = rec, visual = visual)
            assertThat(player.faults()).describedAs(id).isEmpty()
            val effect = id.removePrefix("demo-shader-")
            assertThat(effects).containsExactly(effect)
            assertThat(rec.calls.count { it.verb == "spawn" }).describedAs(id).isEqualTo(1)
            assertThat(rec.calls.filter { it.verb == "remove" }.map { it.tick }).describedAs(id)
                .containsExactly(100)
        }
    }
}
