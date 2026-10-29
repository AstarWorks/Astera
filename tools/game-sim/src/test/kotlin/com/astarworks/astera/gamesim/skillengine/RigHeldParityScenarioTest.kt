package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.FrameTables
import com.astarworks.astera.core.application.skillengine.load.ItemFrames
import com.astarworks.astera.core.application.skillengine.load.ItemFramesDecoder
import com.astarworks.astera.core.application.skillengine.load.ModelPart
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ADR-0303 open decisions 1 / 3 / 4 and the "too small or offset" rule (design-gaps-v1 section 5).
 *
 * Parity is decided by numbers: for every `content/frames/<item>.yaml` part drawn with `thirdperson_righthand` (the held mesh), the tip and the
 * `long` axis the frames file publishes must equal what the client draws for the model JSON, computed here from the JSON alone
 * (`display.thirdperson_righthand` chain: q = RotY(180) * (T + Rxyz * (S * (p/16 - 0.5))), the chain of content/frames/couture-rapier.yaml).
 * The tip is the top of the model's tip element(s) (name `tip`, `*_tip`, `tip_point`), centred on the other axes. A mismatch is the model JSON's or
 * the frames file's fault (owner: models), never the skill's: the skill has no context, scale or offset field to correct it.
 */
class RigHeldParityScenarioTest : SkillEngineScenario() {
    private data class Row(val item: String, val part: String, val tipErr: Double, val angleErr: Double, val note: String)

    private fun rot(axis: Char, deg: Double): Array<DoubleArray> {
        val a = Math.toRadians(deg)
        val c = cos(a)
        val s = sin(a)
        return when (axis) {
            'x' -> arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, c, -s), doubleArrayOf(0.0, s, c))
            'y' -> arrayOf(doubleArrayOf(c, 0.0, s), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-s, 0.0, c))
            else -> arrayOf(doubleArrayOf(c, -s, 0.0), doubleArrayOf(s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        }
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { a[i][it] * b[it][j] } } }
    private fun apply(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { i -> (0..2).sumOf { m[i][it] * v[it] } }

    private fun nums(o: JsonObject, key: String, default: Double): DoubleArray =
        (o[key] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }?.toDoubleArray()?.takeIf { it.size == 3 } ?: DoubleArray(3) { default }

    /** (tip, long) in the display-local frame, from the model JSON's display block [display]. */
    private fun chain(display: JsonObject, p: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val r = nums(display, "rotation", 0.0)
        val t = nums(display, "translation", 0.0).map { it / 16.0 }.toDoubleArray()
        val s = nums(display, "scale", 1.0)
        val m = mul(mul(rot('x', r[0]), rot('y', r[1])), rot('z', r[2]))
        val local = DoubleArray(3) { s[it] * (p[it] / 16.0 - 0.5) }
        val moved = apply(m, local).let { DoubleArray(3) { i -> t[i] + it[i] } }
        val y180 = rot('y', 180.0)
        return apply(y180, moved) to apply(y180, apply(m, doubleArrayOf(0.0, 1.0, 0.0)))
    }

    /**
     * A flat handheld sprite (content/models/generated/items/<id>.model.json, parent minecraft:item/handheld, docs/art/arsenal-flat-art.md): the tip is
     * the farthest opaque pixel along the handle-to-head diagonal (x + y) of the texture, `long` is that diagonal (1,1,0) in the sprite plane, both through
     * the vanilla handheld display chain.
     */
    private fun measureSprite(item: String, part: ModelPart, modelFile: java.nio.file.Path): Row {
        val json = Json.parseToJsonElement(Files.readString(modelFile)).jsonObject
        val layer = json["textures"]?.jsonObject?.get("layer0")?.jsonPrimitive?.content?.substringAfter(':')
            ?: return Row(item, part.name, Double.NaN, Double.NaN, "sprite model has no textures.layer0")
        val png = contentDir.resolve("textures/$layer.png")
        val image = javax.imageio.ImageIO.read(png.toFile()) ?: return Row(item, part.name, Double.NaN, Double.NaN, "texture $layer.png is unreadable")
        val k = 16.0 / image.width
        var best = Double.NEGATIVE_INFINITY
        var px = 0.0
        var py = 0.0
        for (r in 0 until image.height) for (c in 0 until image.width) {
            if ((image.getRGB(c, r) ushr 24) == 0) continue
            val x = (c + 0.5) * k
            val y = 16.0 - (r + 0.5) * k
            if (x + y > best) { best = x + y; px = x; py = y }
        }
        val display = when (part.display) {
            "fixed" -> mapOf("rotation" to doubleArrayOf(0.0, 180.0, 0.0), "translation" to doubleArrayOf(0.0, 0.0, 0.0), "scale" to doubleArrayOf(0.5, 0.5, 0.5))
            "thirdperson_righthand" -> mapOf("rotation" to doubleArrayOf(0.0, -90.0, 55.0), "translation" to doubleArrayOf(0.0, 4.0, 0.5), "scale" to doubleArrayOf(0.85, 0.85, 0.85))
            else -> return Row(item, part.name, Double.NaN, Double.NaN, "no vanilla handheld display for ${part.display}")
        }
        val r = display.getValue("rotation")
        val m = mul(mul(rot('x', r[0]), rot('y', r[1])), rot('z', r[2]))
        val t = display.getValue("translation").map { it / 16.0 }
        val s = display.getValue("scale")
        val local = doubleArrayOf(s[0] * (px / 16.0 - 0.5), s[1] * (py / 16.0 - 0.5), s[2] * (8.0 / 16.0 - 0.5))
        val y180 = rot('y', 180.0)
        val moved = apply(m, local).let { doubleArrayOf(t[0] + it[0], t[1] + it[1], t[2] + it[2]) }
        val tip = apply(y180, moved)
        val long = apply(y180, apply(m, doubleArrayOf(1.0, 1.0, 0.0)))
        val fileTip = part.points["tip"] ?: return Row(item, part.name, Double.NaN, Double.NaN, "frames part declares no tip point")
        val fileLong = part.axes["long"] ?: return Row(item, part.name, Double.NaN, Double.NaN, "frames part declares no long axis")
        val tipErr = sqrt((tip[0] - fileTip.x).let { it * it } + (tip[1] - fileTip.y).let { it * it } + (tip[2] - fileTip.z).let { it * it })
        val fl = doubleArrayOf(fileLong.x, fileLong.y, fileLong.z)
        val dot = (0..2).sumOf { long[it] * fl[it] } / (sqrt(long.sumOf { it * it }) * sqrt(fl.sumOf { it * it }))
        return Row(item, part.name, tipErr, Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0))), "")
    }

    private val tipName = Regex("(^|_)tip(_point)?$")

    private fun measure(item: String, part: ModelPart): Row {
        val model = part.model.substringAfter(':')
        val file = sequenceOf("items", "").map { contentDir.resolve("models/$it/$model.model.json".replace("//", "/")) }.firstOrNull { Files.isRegularFile(it) }
            ?: contentDir.resolve("models/generated/items/$model.model.json").takeIf { Files.isRegularFile(it) }?.let { return measureSprite(item, part, it) }
            ?: return Row(item, part.name, Double.NaN, Double.NaN, "model '${part.model}' has no content/models/**/$model.model.json")
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val display = json["display"]?.jsonObject?.get(part.display)?.jsonObject
            ?: return Row(item, part.name, Double.NaN, Double.NaN, "model JSON has no display.${part.display}")
        val tips = (json["elements"] as JsonArray).map { it.jsonObject }.filter { tipName.containsMatchIn(it["name"]?.jsonPrimitive?.content ?: "") }
        if (tips.isEmpty()) return Row(item, part.name, Double.NaN, Double.NaN, "no model element is named tip / *_tip / tip_point")
        fun v(e: JsonObject, k: String) = e[k]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! }
        val centres = tips.map { e -> DoubleArray(3) { (v(e, "from")[it] + v(e, "to")[it]) / 2.0 } }
        val top = tips.maxOf { e -> max(v(e, "from")[1], v(e, "to")[1]) }
        val p = doubleArrayOf(centres.map { it[0] }.average(), top, centres.map { it[2] }.average())
        val (tip, long) = chain(display, p)
        val fileTip = part.points["tip"] ?: return Row(item, part.name, Double.NaN, Double.NaN, "frames part declares no tip point")
        val fileLong = part.axes["long"] ?: return Row(item, part.name, Double.NaN, Double.NaN, "frames part declares no long axis")
        val tipErr = sqrt((tip[0] - fileTip.x).let { it * it } + (tip[1] - fileTip.y).let { it * it } + (tip[2] - fileTip.z).let { it * it })
        val fl = doubleArrayOf(fileLong.x, fileLong.y, fileLong.z)
        val dot = (0..2).sumOf { long[it] * fl[it] } / (sqrt(long.sumOf { it * it }) * sqrt(fl.sumOf { it * it }))
        return Row(item, part.name, tipErr, Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0))), "")
    }

    @Test
    fun everyShippedHeldMeshMatchesTheModelJson() {
        val tables = ItemFramesDecoder.loadDirectory(contentDir.resolve("frames")).first
        val rows = tables.values.flatMap { f -> f.handParts().map { measure(f.item, it) } }
        assertThat(rows).describedAs("shipped frames files with a thirdperson_righthand part").isNotEmpty
        println("HELD-PARITY item part tip_error_blocks angle_error_deg note")
        rows.forEach { println("HELD-PARITY ${it.item} ${it.part} %.4f %.3f ${it.note}".format(it.tipErr, it.angleErr)) }
        val bad = rows.filter { it.note.isNotEmpty() || it.tipErr > 0.01 || it.angleErr > 1.0 }
        assertThat(bad).describedAs("held meshes whose frames tip is more than 0.01 blocks or whose long axis is more than 1 degree off the model JSON").isEmpty()
    }

    // ---- decision 1: the engine picks the part ----------------------------------------------------------------------

    override val frameTables: FrameTables by lazy {
        val base = super.frameTables
        val plain = ItemFrames("plain-blade", mapOf("held" to ModelPart("held", "astera:plain-blade", "fixed", 1.0, mapOf("tip" to Vec3(0.0, 1.0, 0.0)), mapOf("long" to Vec3(0.0, 1.0, 0.0), "edge" to Vec3(1.0, 0.0, 0.0)))))
        object : FrameTables {
            override fun item(item: String): ItemFrames? = if (item == "plain-blade") plain else base.item(item)
        }
    }

    private fun skill(item: String, vararg rows: String): String = listOf(
        "apiVersion: astera/v3", "kind: Skill", "id: held-probe", "item: $item",
        "machine:", "  start: s", "  states:", "    s:", "      for: 6", "      enter:",
    ).plus(rows.map { "        - $it" }).joinToString("\n") + "\n"

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> throw AssertionError("expected a load error but it compiled")
        is Result.Failure -> r.error.message
    }

    @Test
    fun aFramesFileWithoutTheHeldHandPartIsALoadError() {
        assertThat(failure(skill("plain-blade", "{id: arm, op: rig, parent: caster, for: 6}")))
            .contains("rig 's/arm'").contains("has no part with display: thirdperson_righthand")
    }

    @Test
    fun theAuthorNeverNamesTheHeldPartOrScalesIt() {
        assertThat(failure(skill("couture-rapier", "{id: arm, op: rig, parent: caster, part: held, for: 6}"))).contains("chosen by the engine")
        assertThat(failure(skill("couture-rapier", "{id: arm, op: rig, parent: caster, scale: 2, for: 6}"))).contains("scale")
        assertThat(failure(skill("couture-rapier", "{id: arm, op: rig, parent: caster, context: fixed, for: 6}"))).contains("context")
    }

    @Test
    fun theDumpPrintsTheHeldMeshAndTheTrailDefaults() {
        val rig = when (val r = loadInline(skill("couture-rapier", "{id: arm, op: rig, parent: caster, for: 6}"))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }
        val text = com.astarworks.astera.core.application.skillengine.compose.ExpansionDump.render(rig.table)
        println(text)
        assertThat(text).contains("held_part").contains("held_hand (thirdperson_righthand)").contains("held_length").contains("hand_anchor")
    }

    @Test
    fun theDumpPrintsTheTrailSampleDefaults() {
        val sk = when (val r = loadInline(skill("couture-rapier", "{id: arm, op: rig, parent: caster, for: 6}", "{id: tr, op: ribbon, of: arm.tip, history: 12, width: 0.22, color: \"#ffffff\", style: soft, for: 6}"))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }
        val text = com.astarworks.astera.core.application.skillengine.compose.ExpansionDump.render(sk.table)
        println(text)
        assertThat(text).contains("\"history_ticks\":12").contains("\"step_deg\":30").contains("\"lag_ticks\":0")
        assertThat(failure(skill("couture-rapier", "{id: arm, op: rig, parent: caster, for: 6}", "{id: tr, op: ribbon, of: arm.tip, history: 12, segments: 8, width: 0.22, color: \"#ffffff\", style: soft, for: 6}"))).contains("segments")
    }
}
