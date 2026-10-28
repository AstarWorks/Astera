package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.combat.adapter.paper.skillengine.Intrinsic
import com.astarworks.astera.combat.adapter.paper.skillengine.toDisplayWrite
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * ADR-0303 2.1 / SKL-22: block, item and text displays are placed and turned about the middle of what they draw, as v1
 * (`VfxTimeline`: `translation = P + Rs*T - L*(0.5*S)` for blocks; `VfxQuad.transform`: `translation = L*(-.0125*S.x, -.125*S.y, 0)` for
 * text quads; items are centred by the vanilla model). The yaml `scale` is the vanilla scale for all three (text included, no hidden x5).
 * The cast runs through the real player; the pose each spawn hands the gateway goes through the real [toDisplayWrite] with the gateway's
 * own [Intrinsic] and is compared with the v1 formula written out here.
 */
class DisplayPivotScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: pivot-probe", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: 20", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    @Test
    fun blockItemAndTextComposeAroundTheirMiddleLikeV1() {
        val compiled = (loadInline(
            skill(
                "{id: b, op: display, kind: block, block: minecraft:white_concrete, anchor: caster, offset: {up: 1.0, forward: 2.0}, rotate: [{axis: up, deg: 40}, {axis: left, deg: 25}], scale: [0.5, 2.0, 0.25], for: 10}",
                "{id: i, op: display, kind: item, item: couture-rapier, anchor: caster, offset: {up: 1.0}, rotate: [{axis: up, deg: 70}], scale: [1.5, 1.5, 1.5], for: 10}",
                "{id: t, op: display, kind: text, text: \"●\", color: \"#FFD66E\", anchor: caster, offset: {up: 1.0}, rotate: [{axis: left, deg: 90}, {axis: up, deg: -50}], scale: [7.5, 16.5, 1.0], for: 10}",
            ),
        ) as Result.Success).value

        val rec = GatewayRecorder()
        val seen = LinkedHashMap<String, VisualPose>()
        val spy = object : ISkillVisualGateway by rec.visual {
            override fun spawnBlock(cast: CastRef, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?): VisualHandle {
                seen["block"] = pose
                return rec.visual.spawnBlock(cast, block, pose, brightness, rigShading, view, glow)
            }

            override fun spawnMesh(cast: CastRef, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook, view: DisplayView, glow: String?): VisualHandle {
                seen["item"] = pose
                return rec.visual.spawnMesh(cast, item, part, pose, brightness, look, view, glow)
            }

            override fun spawnText(cast: CastRef, text: String, backgroundHex: String, backgroundAlpha: Double, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView): VisualHandle {
                seen["text"] = pose
                return rec.visual.spawnText(cast, text, backgroundHex, backgroundAlpha, shadow, seeThrough, pose, brightness, view)
            }
        }
        val player = playProbe(compiled, ticks = 4, rec = rec, visual = spy)
        assertThat(player.faults()).isEmpty()
        assertThat(seen.keys).containsExactly("block", "item", "text")

        val yamlScale = mapOf("block" to Vec3(0.5, 2.0, 0.25), "item" to Vec3(1.5, 1.5, 1.5), "text" to Vec3(7.5, 16.5, 1.0))
        val intrinsic = mapOf("block" to Intrinsic.BLOCK, "item" to Intrinsic.ITEM_FIXED, "text" to Intrinsic.TEXT_QUAD)
        val eps = Offset.offset(1e-9)
        for ((name, pose) in seen) {
            val write = toDisplayWrite(pose.frame, pose.size, intrinsic.getValue(name), DisplayBillboard.FIXED, 0)
            val scale = yamlScale.getValue(name)
            val rot = pose.frame.rotation
            // v1: the author's scale is the display scale, whatever the kind
            assertThat(write.transform.scale).isEqualTo(scale)
            assertThat(write.transform.rotation).isEqualTo(rot)
            val v1 = when (name) {
                "block" -> rot.rotate(Vec3(0.5 * scale.x, 0.5 * scale.y, 0.5 * scale.z)) * -1.0 // - L*(0.5*S)
                "text" -> rot.rotate(Vec3(-0.0125 * scale.x, -0.125 * scale.y, 0.0)) // VfxQuad.transform
                else -> Vec3.ZERO // item_display fixed: centred by the model
            }
            assertThat(write.transform.translation.x).isCloseTo(v1.x, eps)
            assertThat(write.transform.translation.y).isCloseTo(v1.y, eps)
            assertThat(write.transform.translation.z).isCloseTo(v1.z, eps)
        }
        // a rotated, non-unit block and a text quad must carry a translation (a corner pivot would leave it zero)
        for (name in listOf("block", "text")) {
            val pose = seen.getValue(name)
            assertThat(toDisplayWrite(pose.frame, pose.size, intrinsic.getValue(name), DisplayBillboard.FIXED, 0).transform.translation).isNotEqualTo(Vec3.ZERO)
        }
    }
}
