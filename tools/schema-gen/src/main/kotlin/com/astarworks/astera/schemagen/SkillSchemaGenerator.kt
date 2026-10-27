package com.astarworks.astera.schemagen

import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.model.FrameDecl
import com.astarworks.astera.core.domain.skillengine.model.GameplaySection
import com.astarworks.astera.core.domain.skillengine.model.PresentationSection
import com.astarworks.astera.core.domain.skillengine.model.TimelineEntry
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.GameplayOp
import com.astarworks.astera.core.domain.skillengine.op.PresentationOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

/**
 * JSON Schema (Draft 7) generator for the skill engine v2 documents (ADR-0301): `kind: Skill`, `kind: Patch`
 * and `kind: ItemFrames`.
 *
 * The op schemas are derived by reflection over the sealed hierarchy under [SkillOp]; nothing here lists an
 * op by hand, so a new op subclass appears in the schema on the next `generateSchemas` run (and the drift
 * guard `checkSchemas` fails until the regenerated files are committed). YAML shapes follow the frozen coin-2
 * grammar: `op: <snake_case class name>`, snake_case field names, flattened motion / hit-volume specs, and
 * the three value forms of F-7 (literal, `${param}`, `= CEL`) for every `Value<T>`.
 *
 * Ops keep `additionalProperties: true`: the typed decoder owns the strict unknown-key check (it knows each
 * op's real key set); the schema types the keys it can derive and rejects wrong op names, wrong sections and
 * badly shaped values. YAML anchors and merge keys are resolved by the YAML parser before validation; the
 * documents accept stash keys (`defs`, `x-*`, `_*`) so anchors can be declared without touching the model.
 */
object SkillSchemaGenerator {

    private val pretty = Json { prettyPrint = true }

    const val ID_BASE: String = "https://astera.astarworks.com/schemas/"

    /** Reflected view of one YAML op name (a motion op expands to one entry per [MotionSpec] leaf). */
    data class OpEntry(val yamlName: String, val kclass: KClass<*>, val section: String, val spec: KClass<*>? = null)

    // ---- reflection over the sealed hierarchy ----------------------------------------------------------------

    private fun leaves(root: KClass<*>): List<KClass<*>> {
        val out = linkedMapOf<String, KClass<*>>()
        fun walk(k: KClass<*>) {
            if (k.isSealed) k.sealedSubclasses.forEach { walk(it) } else out[k.qualifiedName ?: k.toString()] = k
        }
        walk(root)
        return out.values.sortedBy { it.qualifiedName }
    }

    /** Every concrete op class under [SkillOp]. */
    fun opClasses(): List<KClass<*>> = leaves(SkillOp::class)

    /** Every YAML `op:` name with the class that implements it. */
    fun opEntries(): List<OpEntry> = opClasses().flatMap { k ->
        val section = if (k.isSubclassOf(GameplayOp::class)) "gameplay" else "presentation"
        val specParam = k.primaryConstructor?.parameters?.firstOrNull { classOf(it.type)?.isSubclassOf(MotionSpec::class) == true }
        if (specParam != null) {
            leaves(MotionSpec::class).map { OpEntry(snake(it.simpleName!!), k, section, it) }
        } else {
            // the decoder names `StatusOp.ApplyStatus` plain `status` and `ControlOp.RaiseEvent` plain `event`
            val yaml = when (k.simpleName) {
                "ApplyStatus" -> "status"
                "RaiseEvent" -> "event"
                else -> snake(k.simpleName!!)
            }
            listOf(OpEntry(yaml, k, section))
        }
    }.sortedBy { it.yamlName }

    // ---- naming ----------------------------------------------------------------------------------------------

    fun snake(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2").lowercase()

    /** Kotlin property -> YAML key (frozen grammar renames). */
    private fun yamlKey(owner: KClass<*>?, property: String): String = when {
        property == "whenExpr" -> "when"
        property == "untilEvent" -> "until"
        property == "afterTicks" -> "after"
        property == "everyTicks" -> "every"
        property == "ops" -> "do"
        owner == com.astarworks.astera.core.domain.skillengine.op.TrackKey::class && property == "tick" -> "t"
        owner == com.astarworks.astera.core.domain.skillengine.op.StatusOp.ApplyStatus::class && property == "statusId" -> "status"
        owner == com.astarworks.astera.core.domain.skillengine.modifier.ExposedParam::class && property == "name" -> "id"
        else -> snake(property)
    }

    private fun classOf(t: KType): KClass<*>? = t.classifier as? KClass<*>

    // ---- schema fragments ------------------------------------------------------------------------------------

    private fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
    private fun ref(name: String): JsonObject = obj { put("\$ref", "#/definitions/$name") }
    private fun type(name: String): JsonObject = obj { put("type", name) }
    private val anyValue: JsonObject = JsonObject(emptyMap())
    private val vec3: JsonObject = obj {
        put("type", "array"); put("items", type("number")); put("minItems", 3); put("maxItems", 3)
    }
    private val quat: JsonObject = obj {
        put("type", "array"); put("items", type("number")); put("minItems", 4); put("maxItems", 4)
    }
    private val paramRef: JsonObject = obj {
        put("type", "string"); put("pattern", "^\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}$")
        put("description", "\${param}: whole-string reference to a params entry")
    }
    private val celExpr: JsonObject = obj {
        put("type", "string"); put("pattern", "^= ?.+")
        put("description", "= <CEL expression>, compiled once at load")
    }
    private fun arrayOf(items: JsonElement): JsonObject = obj { put("type", "array"); put("items", items) }
    private fun anyOf(vararg parts: JsonElement): JsonObject = obj { put("anyOf", JsonArray(parts.toList())) }

    private val SIMPLE_TYPES = setOf("Double", "Float", "Int", "Long", "Boolean", "String", "PointRef", "AxisRef", "FrameName", "Vec3", "Quaternion")

    private fun literalSchema(t: KType?): JsonElement {
        val k = t?.let { classOf(it) } ?: return anyValue
        return when (k.simpleName) {
            "Double", "Float" -> type("number")
            "Int", "Long" -> type("integer")
            "Boolean" -> type("boolean")
            "String", "PointRef", "AxisRef", "FrameName" -> type("string")
            "Vec3" -> vec3
            "Quaternion" -> quat
            else -> anyValue
        }
    }

    private fun valueKind(inner: KType?): String = when (inner?.let { classOf(it) }?.simpleName) {
        "Double", "Float" -> "number"
        "Int", "Long" -> "integer"
        "Boolean" -> "boolean"
        "Vec3" -> "vec3"
        "Quaternion" -> "quat"
        else -> "any"
    }

    /** F-7: literal | `${param}` | `= CEL`, shared as `Value_<kind>` definitions. */
    private fun valueSchema(inner: KType?): JsonElement =
        if (inner != null && classOf(inner)?.simpleName == "String") type("string") else ref("Value_${valueKind(inner)}")

    /** The shared value definitions every document carries. */
    private fun valueDefs(): Map<String, JsonElement> {
        val literals = linkedMapOf<String, JsonElement>(
            "number" to type("number"),
            "integer" to type("integer"),
            "boolean" to type("boolean"),
            "vec3" to vec3,
            "quat" to quat,
            "any" to anyValue,
        )
        return literals.entries.associate { (k, lit) ->
            "Value_$k" to anyOf(lit, paramRef, celExpr).let { a ->
                if (k == "any") obj { put("description", "literal, \${param} or = CEL expression") } else a
            }
        }
    }

    // ---- type -> schema --------------------------------------------------------------------------------------

    private class Ctx(val patch: Boolean) {
        val defs = linkedMapOf<String, JsonElement>()
        val building = mutableSetOf<String>()
    }

    private fun typeSchema(t: KType, ctx: Ctx): JsonElement {
        val k = classOf(t) ?: return anyValue
        return when {
            k == Value::class -> valueSchema(t.arguments.firstOrNull()?.type)
            k == List::class -> {
                val inner = t.arguments.firstOrNull()?.type
                val ik = inner?.let { classOf(it) }
                when {
                    ik != null && ik.isSubclassOf(GameplayOp::class) && ik != SkillOp::class -> arrayOf(ref("GameplayOp"))
                    ik != null && ik.isSubclassOf(PresentationOp::class) -> arrayOf(ref("PresentationOp"))
                    inner != null -> arrayOf(typeSchema(inner, ctx))
                    else -> type("array")
                }
            }
            k == Map::class -> {
                val v = t.arguments.getOrNull(1)?.type
                obj { put("type", "object"); put("additionalProperties", if (v != null) typeSchema(v, ctx) else anyValue) }
            }
            k == Pair::class -> anyValue
            k.simpleName == "ParamValue" -> anyOf(type("number"), type("string"), type("boolean"))
            k.simpleName == "TimelineAt" -> anyOf(
                obj { put("type", "integer"); put("minimum", 0) },
                obj { put("type", "string"); put("pattern", "^[A-Za-z_][A-Za-z0-9_]*([+-][0-9]+)?\$") },
            )
            k.java.isEnum -> obj {
                put("type", "string")
                put("enum", JsonArray(k.java.enumConstants.map { JsonPrimitive((it as Enum<*>).name.lowercase()) }))
            }
            k.simpleName in SIMPLE_TYPES -> literalSchema(t)
            k.isSealed -> sealedSchema(k)
            k.isData && k.primaryConstructor != null -> ref(dataDef(k, ctx))
            else -> literalSchema(t)
        }
    }

    /** Nested sealed types decode by keyword (`window: once`, `target: victim`, `{enemies: ...}`); the decoder is strict. */
    private fun sealedSchema(k: KClass<*>): JsonElement = obj {
        put("type", buildJsonArray { add("string"); add("object") })
        put("description", "one of: " + leaves(k).joinToString(", ") { snake(it.simpleName!!) })
    }

    private fun dataDef(k: KClass<*>, ctx: Ctx): String {
        val name = k.simpleName!! + if (ctx.patch) "Patch" else ""
        if (name in ctx.defs || name in ctx.building) return name
        ctx.building += name
        val ctor = k.primaryConstructor!!
        val props = linkedMapOf<String, JsonElement>()
        val required = mutableListOf<String>()
        for (p in ctor.parameters) {
            val key = yamlKey(k, p.name!!)
            props[key] = typeSchema(p.type, ctx)
            if (!ctx.patch && isRequired(p)) required += key
        }
        if (ctx.patch && "id" in props) required += "id"
        ctx.defs[name] = obj {
            put("type", "object")
            put("properties", JsonObject(props))
            if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
            put("additionalProperties", true)
        }
        ctx.building -= name
        return name
    }

    private fun isRequired(p: KParameter): Boolean = !p.isOptional && !p.type.isMarkedNullable && classOf(p.type) != Boolean::class

    // ---- ops -------------------------------------------------------------------------------------------------

    private val valueKeys = linkedMapOf(
        "id" to obj { put("type", "string"); put("minLength", 1); put("description", "unique within the skill (patches address ops by id)") },
        "op" to type("string"),
        "when" to valueSchema(kotlin.reflect.typeOf<Boolean>()),
    )

    private fun opProperties(e: OpEntry, ctx: Ctx): LinkedHashMap<String, JsonElement> {
        val props = LinkedHashMap<String, JsonElement>(valueKeys)
        props["op"] = obj { put("const", e.yamlName) }
        for (p in e.kclass.primaryConstructor!!.parameters) {
            val name = p.name!!
            if (name == "id" || name == "whenExpr") continue
            val pk = classOf(p.type)
            when {
                pk != null && pk.isSubclassOf(MotionSpec::class) -> {
                    for (sp in e.spec!!.primaryConstructor?.parameters.orEmpty()) {
                        val key = yamlKey(e.spec, sp.name!!)
                        // `Pose.keys` etc. keep their names; `Spin.untilEvent` -> `until`.
                        props[key] = typeSchema(sp.type, ctx)
                    }
                }
                pk != null && pk.isSubclassOf(HitVolume::class) -> {
                    for (v in leaves(HitVolume::class)) props[snake(v.simpleName!!)] = ref(dataDef(v, ctx))
                    if (e.kclass.simpleName == "Projectile" || e.kclass.simpleName == "Aoe") props["radius"] = ref("Value_number")
                }
                else -> props[yamlKey(e.kclass, name)] = typeSchema(p.type, ctx)
            }
        }
        return props
    }

    private fun opDef(e: OpEntry, ctx: Ctx): JsonObject {
        val props = opProperties(e, ctx)
        val oneOfVolume = e.kclass.primaryConstructor!!.parameters.any { classOf(it.type)?.isSubclassOf(HitVolume::class) == true }
        return obj {
            put("type", "object")
            put("properties", JsonObject(props))
            put("required", buildJsonArray { add("id"); add("op") })
            put("additionalProperties", true)
            if (oneOfVolume) {
                // projectile and aoe also accept a bare `radius:` (a sphere on the frame the volume rides on)
                val shorthand = e.kclass.simpleName == "Projectile" || e.kclass.simpleName == "Aoe"
                put("oneOf", JsonArray(
                    leaves(HitVolume::class).map { v -> obj { put("required", buildJsonArray { add(snake(v.simpleName!!)) }) } } +
                        (if (shorthand) listOf(obj { put("required", buildJsonArray { add("radius") }) }) else emptyList()),
                ))
            }
        }
    }

    private fun addOps(ctx: Ctx) {
        val entries = opEntries()
        for (e in entries) ctx.defs["op_${e.yamlName}"] = opDef(e, ctx)
        for (section in listOf("gameplay", "presentation")) {
            val names = entries.filter { it.section == section }
            val defName = if (section == "gameplay") "GameplayOp" else "PresentationOp"
            val full = obj {
                put("type", "object")
                put("required", buildJsonArray { add("id"); add("op") })
                put("properties", obj {
                    put("id", valueKeys["id"]!!)
                    put("op", obj {
                        put("type", "string")
                        put("enum", JsonArray(names.map { JsonPrimitive(it.yamlName) }))
                    })
                })
                put("allOf", JsonArray(names.map { n ->
                    obj {
                        put("if", obj { put("properties", obj { put("op", obj { put("const", n.yamlName) }) }); put("required", buildJsonArray { add("op") }) })
                        put("then", ref("op_${n.yamlName}"))
                    }
                }))
            }
            if (!ctx.patch) {
                ctx.defs[defName] = full
            } else {
                // Patch entries are partial (merged by id): `op` is optional, but when present it must be a valid one.
                ctx.defs[defName] = obj {
                    put("type", "object")
                    put("required", buildJsonArray { add("id") })
                    put("properties", obj {
                        put("id", valueKeys["id"]!!)
                        put("\$patch", obj { put("enum", buildJsonArray { add("delete") }) })
                    })
                    put("if", obj { put("required", buildJsonArray { add("op") }) })
                    put("then", obj {
                        put("properties", obj { put("op", full["properties"]!!.let { (it as JsonObject)["op"]!! }) })
                        put("allOf", full["allOf"]!!)
                    })
                }
            }
        }
    }

    // ---- documents -------------------------------------------------------------------------------------------

    private fun section(k: KClass<*>, ctx: Ctx): JsonElement {
        val ctor = k.primaryConstructor!!
        val props = linkedMapOf<String, JsonElement>()
        for (p in ctor.parameters) {
            val key = yamlKey(k, p.name!!)
            props[key] = if (classOf(p.type) == List::class && classOf(p.type.arguments.first().type!!) == TimelineEntry::class) {
                arrayOf(ref(rowDef(k, p, ctx)))
            } else {
                typeSchema(p.type, ctx)
            }
        }
        return obj {
            put("type", "object")
            put("properties", JsonObject(props))
            put("additionalProperties", false)
        }
    }

    private fun rowDef(sectionClass: KClass<*>, p: KParameter, ctx: Ctx): String {
        val opType = p.type.arguments.first().type!!.arguments.first().type!!
        val opsDef = if (classOf(opType)!!.isSubclassOf(GameplayOp::class)) "GameplayOp" else "PresentationOp"
        val name = (if (opsDef == "GameplayOp") "Gameplay" else "Presentation") + "Row" + if (ctx.patch) "Patch" else ""
        val props = linkedMapOf<String, JsonElement>(
            "id" to valueKeys["id"]!!,
            "at" to typeSchema(kotlin.reflect.typeOf<com.astarworks.astera.core.domain.skillengine.model.TimelineAt>(), ctx),
            "do" to arrayOf(ref(opsDef)),
            "every" to obj { put("type", "integer"); put("minimum", 1) },
            "until" to type("string"),
            "when" to valueSchema(kotlin.reflect.typeOf<Boolean>()),
        )
        val required = if (ctx.patch) listOf("id") else listOf("id", "at", "do")
        ctx.defs[name] = obj {
            put("type", "object")
            put("properties", JsonObject(props))
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
            put("additionalProperties", false)
        }
        return name
    }

    private val metaProps = linkedMapOf<String, JsonElement>(
        "apiVersion" to obj { put("const", "astera/v2") },
    )

    private val localizedText: JsonObject = obj {
        put("type", "object")
        put("properties", obj { put("ja", type("string")); put("en", type("string")) })
        put("required", buildJsonArray { add("ja"); add("en") })
        put("additionalProperties", false)
    }

    /** `meta:` header of a skill (cooldown, cost, i18n name); mirrors `YamlSkillDecoder.decodeMeta`. */
    private val skillMetaSchema: JsonObject = obj {
        put("type", "object")
        put("properties", obj {
            put("name", localizedText)
            put("description", localizedText)
            put("cooldown_ticks", obj { put("type", "integer"); put("minimum", 0) })
            put("resource_type", obj { put("enum", buildJsonArray { add("ammo"); add("energy") }) })
            put("resource_cost", obj { put("type", "integer"); put("minimum", 0) })
            put("ego_cost", obj { put("type", "integer"); put("minimum", 0) })
            put("reload_ticks", obj { put("type", "integer"); put("minimum", 0) })
            put("ready_cue", type("boolean"))
            put("tags", arrayOf(type("string")))
        })
        put("additionalProperties", false)
    }

    private fun docDef(kind: String, ctx: Ctx): JsonObject {
        val gameplay = section(GameplaySection::class, ctx)
        val presentation = section(PresentationSection::class, ctx)
        return when (kind) {
            "Skill" -> obj {
                put("type", "object")
                put("required", buildJsonArray { add("apiVersion"); add("kind") })
                put("properties", obj {
                    put("apiVersion", metaProps["apiVersion"]!!)
                    put("kind", obj { put("const", "Skill") })
                    put("id", obj { put("type", "string"); put("minLength", 1); put("pattern", "^[^@]+$") })
                    put("item", type("string"))
                    put("importance", type("integer"))
                    put("meta", skillMetaSchema)
                    put("frames", arrayOf(ref(dataDef(FrameDecl::class, ctx))))
                    put("gameplay", gameplay)
                    put("presentation", presentation)
                    put("defs", anyValue)
                })
                put("additionalProperties", false)
                put("patternProperties", obj { put("^(x-|_)", anyValue) })
            }
            else -> obj {
                put("type", "object")
                put("required", buildJsonArray { add("apiVersion"); add("kind"); add("target"); add("patchKind") })
                put("properties", obj {
                    put("apiVersion", metaProps["apiVersion"]!!)
                    put("kind", obj { put("const", "Patch") })
                    put("target", obj { put("type", "string"); put("minLength", 1) })
                    put("variant", obj { put("type", "string"); put("minLength", 1); put("pattern", "^[^@]+$") })
                    put("patchKind", obj { put("enum", buildJsonArray { add("skin"); add("balance") }) })
                    put("frames", arrayOf(ref(dataDef(FrameDecl::class, ctx))))
                    put("gameplay", gameplay)
                    put("presentation", presentation)
                    put("defs", anyValue)
                })
                put("additionalProperties", false)
                put("patternProperties", obj { put("^(x-|_)", anyValue) })
                // skin may only touch presentation.* (compose rejects anything else; the schema says so earlier).
                put("if", obj {
                    put("properties", obj { put("patchKind", obj { put("const", "skin") }) })
                    put("required", buildJsonArray { add("patchKind") })
                })
                put("then", obj {
                    put("properties", obj { put("gameplay", JsonPrimitive(false)); put("frames", JsonPrimitive(false)) })
                })
            }
        }
    }

    private fun itemFramesDoc(): JsonObject = obj {
        put("type", "object")
        put("required", buildJsonArray { add("apiVersion"); add("kind"); add("item"); add("parts") })
        put("properties", obj {
            put("apiVersion", obj { put("const", "astera/v2") })
            put("kind", obj { put("const", "ItemFrames") })
            put("item", obj { put("type", "string"); put("minLength", 1) })
            put("parts", obj {
                put("type", "object")
                put("minProperties", 1)
                put("additionalProperties", obj {
                    put("type", "object")
                    put("required", buildJsonArray { add("points") })
                    put("properties", obj {
                        put("model", type("string"))
                        put("display", type("string"))
                        put("length", type("number"))
                        put("points", obj { put("type", "object"); put("additionalProperties", vec3) })
                        put("axes", obj { put("type", "object"); put("additionalProperties", vec3) })
                    })
                    put("additionalProperties", false)
                })
            })
            put("defs", anyValue)
        })
        put("additionalProperties", false)
        put("patternProperties", obj { put("^(x-|_)", anyValue) })
    }

    // ---- entry points ----------------------------------------------------------------------------------------

    /** Root schema per output file name (without `.schema.json`). */
    fun schemas(): Map<String, JsonObject> {
        val skillCtx = Ctx(patch = false)
        addOps(skillCtx)
        val skill = docDef("Skill", skillCtx)
        val patchCtx = Ctx(patch = true)
        addOps(patchCtx)
        val patch = docDef("Patch", patchCtx)
        val out = linkedMapOf<String, JsonObject>()
        out["skill"] = withDefs(skill, skillCtx.defs, "skill", "Skill document (apiVersion astera/v2, kind Skill)")
        out["skill-patch"] = withDefs(patch, patchCtx.defs, "skill-patch", "Patch document (kind Patch, patchKind skin|balance)")
        out["item-frames"] = withDefs(itemFramesDoc(), emptyMap(), "item-frames", "Item point table (kind ItemFrames, content/frames/<item>.yaml)")
        return out
    }

    private fun withDefs(root: JsonObject, defs: Map<String, JsonElement>, name: String, title: String): JsonObject = obj {
        put("\$schema", "http://json-schema.org/draft-07/schema#")
        put("\$id", "$ID_BASE$name.schema.json")
        put("title", title)
        for ((k, v) in root) put(k, v)
        if (defs.isNotEmpty()) put("definitions", JsonObject((defs + valueDefs()).toSortedMap()))
    }

    /** Writes `skill`, `skill-patch` and `item-frames` into [outputDir]; returns the written names. */
    fun generate(outputDir: Path): List<String> {
        val written = mutableListOf<String>()
        for ((name, schema) in schemas()) {
            outputDir.resolve("$name.schema.json").writeText(pretty.encodeToString(JsonObject.serializer(), schema) + "\n")
            written += name
        }
        return written
    }

}
