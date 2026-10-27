package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.StatusOp

/** Decoders of the status / buff family (YAML `status`, `remove_status`, `cleanse`, ...). */
public fun statusDecoders(): List<OpDecoder> = listOf(StatusOpDecoder)

private val ATTRIBUTE_OPERATIONS = setOf("add", "multiply_base", "multiply_total")

internal object StatusOpDecoder : OpDecoder {
    override val section: OpSection = OpSection.GAMEPLAY

    override val opNames: Set<String> = setOf(
        "status", "remove_status", "cleanse", "ignite", "silence", "modify_attribute", "restore_resource",
    )

    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp {
        val target = readTarget(reader)
        return when (op) {
            "status" -> StatusOp.ApplyStatus(
                id = id,
                whenExpr = whenExpr,
                target = target,
                statusId = reader.requireString("status"),
                stacks = reader.number("stacks") ?: literal(1.0),
                durationTicks = reader.requireNumber("duration_ticks"),
                chance = reader.number("chance"),
            )
            "remove_status" -> StatusOp.RemoveStatus(id, whenExpr, target, reader.requireString("status"))
            "cleanse" -> StatusOp.Cleanse(id, whenExpr, target, reader.bool("negative_only") ?: true)
            "ignite" -> StatusOp.Ignite(id, whenExpr, target, reader.requireNumber("duration_ticks"))
            "silence" -> StatusOp.Silence(id, whenExpr, target, reader.requireNumber("duration_ticks"))
            "modify_attribute" -> {
                val operation = reader.string("operation") ?: "add"
                if (operation !in ATTRIBUTE_OPERATIONS) {
                    reader.fail("${reader.path}.operation: '$operation' is not one of $ATTRIBUTE_OPERATIONS")
                }
                StatusOp.ModifyAttribute(
                    id = id,
                    whenExpr = whenExpr,
                    target = target,
                    attribute = reader.requireString("attribute"),
                    operation = operation,
                    amount = reader.requireNumber("amount"),
                    durationTicks = reader.number("duration_ticks"),
                )
            }
            "restore_resource" -> StatusOp.RestoreResource(
                id, whenExpr, target, reader.requireString("resource"), reader.requireNumber("amount"),
            )
            else -> reader.fail("${reader.path}: status decoder does not own op '$op'")
        }
    }

    /**
     * `target: victim | caster` or `target: {enemies|allies: {around: <point>, radius: <value>}}`.
     * The target is mandatory: a status op without an explicit target is an authoring error.
     */
    private fun readTarget(reader: OpReader): TargetRef {
        val raw = reader.raw("target") ?: reader.fail("${reader.path}.target: required (victim, caster, enemies or allies)")
        return when (raw) {
            "victim" -> TargetRef.Victim
            "caster" -> TargetRef.Caster
            is Map<*, *> -> readArea(reader, raw)
            else -> reader.fail("${reader.path}.target: expected victim, caster or an enemies/allies map, got '$raw'")
        }
    }

    private fun readArea(reader: OpReader, map: Map<*, *>): TargetRef {
        if (map.size != 1) reader.fail("${reader.path}.target: exactly one of enemies / allies")
        val (kind, body) = map.entries.single()
        val fields = body as? Map<*, *> ?: reader.fail("${reader.path}.target.$kind: expected {around, radius}")
        val around = (fields["around"] as? String)?.let(PointRef::parse)
            ?: reader.fail("${reader.path}.target.$kind.around: required point ref")
        val radius = valueOf(reader, fields["radius"], "${reader.path}.target.$kind.radius")
        return when (kind) {
            "enemies" -> TargetRef.Enemies(around, radius)
            "allies" -> TargetRef.Allies(around, radius)
            else -> reader.fail("${reader.path}.target: unknown area '$kind' (enemies or allies)")
        }
    }

    private fun valueOf(reader: OpReader, raw: Any?, path: String): Value<Double> = when {
        raw is Number -> literal(raw.toDouble())
        raw is String && raw.startsWith("= ") -> Value.Expr(raw.removePrefix("= "))
        raw is String && raw.startsWith("\${") && raw.endsWith("}") -> Value.ParamRef(raw.substring(2, raw.length - 1))
        else -> reader.fail("$path: expected a number, \${param} or '= expr', got '$raw'")
    }
}
