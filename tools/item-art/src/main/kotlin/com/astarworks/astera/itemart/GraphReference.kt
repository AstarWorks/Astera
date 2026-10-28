package com.astarworks.astera.itemart

import java.nio.file.Files
import java.nio.file.Path

/** The registry owns the public node vocabulary; publish its reference and authoring schema together. */
internal object GraphReference {
    fun write(dir: Path) {
        Files.createDirectories(dir)
        val table = buildString {
            append("| Node | Params (type; default) | Image/config inputs | Output |\n|---|---|---|---|\n")
            SpriteGraph.SPECS.forEach { (name, spec) ->
                val params = spec.params.entries.joinToString("; ") { (key, p) -> "`$key`: ${p.kind} = `${p.default}`${if (p.choices.isEmpty()) "" else " (${p.choices.joinToString(" / ")})"}" }
                append("| `$name` | $params | ${spec.inputs.entries.joinToString { "${it.key}: ${it.value}" }} | ${spec.output} |\n")
            }
        }
        Files.writeString(dir.resolve("nodes.md"), table)
        val edgeSchema = mapOf("type" to "object", "properties" to mapOf("from" to mapOf("type" to "string", "pattern" to "^[a-zA-Z][a-zA-Z0-9_-]*\\.(image|value)$")), "required" to listOf("from"), "additionalProperties" to false)
        fun ref() = mapOf("$" + "ref" to "#/definitions/parameterEdge")
        fun scalar(p: SpriteGraph.Param): Map<String, Any> = when (p.kind) {
            "number", "boolean", "string" -> buildMap { put("type", p.kind); if (p.choices.isNotEmpty()) put("enum", p.choices.toList()) }
            "color" -> mapOf("type" to "string", "pattern" to "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
            "expression" -> mapOf("type" to "string")
            "vec2" -> mapOf("type" to "array", "items" to mapOf("type" to "number"), "minItems" to 2, "maxItems" to 2)
            "variables" -> mapOf("type" to "object", "additionalProperties" to mapOf("type" to listOf("number", "string")))
            "dots" -> mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to listOf("x", "y", "value", "when").associateWith { mapOf("type" to listOf("number", "string")) }, "required" to listOf("x", "y", "value"), "additionalProperties" to false))
            "colors" -> mapOf("type" to "array", "items" to mapOf("type" to "string", "pattern" to "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$"), "minItems" to 1)
            else -> {
                val key = if (p.kind == "curve") "v" else "color"
                mapOf("type" to "array", "minItems" to 1, "items" to mapOf("type" to "object", "properties" to mapOf("t" to mapOf("type" to "number", "minimum" to 0, "maximum" to 1), key to if (key == "v") mapOf("type" to "number") else mapOf("type" to "string", "pattern" to "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")), "required" to listOf("t", key), "additionalProperties" to false))
            }
        }
        val nodeSchemas = SpriteGraph.SPECS.map { (name, spec) ->
            mapOf("type" to "object", "required" to (listOf("type") + if (spec.requiredInputs.isEmpty()) emptyList() else listOf("inputs")), "additionalProperties" to false, "properties" to mapOf(
                "type" to mapOf("const" to name),
                "params" to mapOf("type" to "object", "additionalProperties" to false, "properties" to spec.params.mapValues { (_, p) -> mapOf("oneOf" to listOf(scalar(p), ref())) }),
                "inputs" to mapOf("type" to "object", "required" to spec.requiredInputs.toList(), "additionalProperties" to false, "properties" to spec.inputs.mapValues { (_, kind) -> mapOf("type" to "string", "pattern" to "^[a-zA-Z][a-zA-Z0-9_-]*\\.${if (kind == "image") "image" else "value"}$") }),
            ))
        }
        val schema = mapOf(
            "\$schema" to "http://json-schema.org/draft-07/schema#",
            "definitions" to mapOf("parameterEdge" to edgeSchema),
            "title" to "Astera sprite source manifest (ADR-0304)", "type" to "object", "additionalProperties" to false,
            "required" to listOf("sprites"),
            "properties" to mapOf(
                "sources" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "object", "properties" to mapOf("file" to mapOf("type" to "string"), "grid" to mapOf("type" to "array", "items" to mapOf("type" to "integer", "minimum" to 1), "minItems" to 2, "maxItems" to 2)), "required" to listOf("file"), "additionalProperties" to false)),
                "sprites" to mapOf("type" to "object", "propertyNames" to mapOf("pattern" to "^[a-z][a-z0-9-]*$"), "additionalProperties" to mapOf("type" to "object", "additionalProperties" to false,
                    "oneOf" to listOf(mapOf("required" to listOf("graph")), mapOf("required" to listOf("from"))),
                    "properties" to mapOf("tags" to mapOf("type" to "array", "items" to mapOf("type" to "string", "pattern" to "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")), "graph" to mapOf("type" to "object", "required" to listOf("nodes"), "additionalProperties" to false, "properties" to mapOf("nodes" to mapOf("type" to "object", "minProperties" to 1, "propertyNames" to mapOf("pattern" to "^[a-zA-Z][a-zA-Z0-9_-]*$"), "additionalProperties" to mapOf("oneOf" to nodeSchemas)))), "from" to mapOf("type" to "string"), "frames" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 256), "size" to mapOf("type" to "integer", "minimum" to 8, "maximum" to 512), "colors" to mapOf("type" to "integer"), "row" to mapOf("type" to "integer", "minimum" to 0), "raw" to mapOf("type" to "boolean"), "outline" to mapOf("type" to "boolean"), "quad_font" to mapOf("type" to "boolean")))),
            ),
        )
        Files.writeString(dir.resolve("sprite-sources.schema.json"), json(schema) + "\n")
        println("sprite graph reference + source schema: $dir")
    }
    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]", transform = ::json)
        else -> v.toString()
    }
}
