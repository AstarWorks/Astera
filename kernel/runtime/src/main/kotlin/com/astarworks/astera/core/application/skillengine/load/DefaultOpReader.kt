package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

/** Per-skill state shared by every reader of one decode run: the registry and the skill-wide set of op ids. */
internal class OpDecodeContext(
    val skillId: String,
    val registry: OpDecoderRegistry,
) {
    private val opIds = HashSet<String>()

    /** Decodes one op map (`{id, op, when, ...}`); [listPath] is the path of the list it sits in. */
    fun decodeOp(section: OpSection, raw: Any?, listPath: String, index: Int): SkillOp {
        val at = "$listPath[$index]"
        val map = raw as? Map<*, *> ?: ValueDecoding.mismatch(skillId, at, "map", raw)
        val id = map["id"] as? String ?: throw DecodeAbort(SkillDecodeError.MissingField(skillId, at, "id"))
        val path = "$listPath[$id]"
        if (!opIds.add(id)) throw DecodeAbort(SkillDecodeError.DuplicateId(skillId, path, id))
        val opName = map["op"] as? String ?: throw DecodeAbort(SkillDecodeError.MissingField(skillId, path, "op"))
        val decoder = registry.find(section, opName)
        if (decoder == null || decoder.section != section) {
            throw DecodeAbort(SkillDecodeError.UnknownOp(skillId, path, opName, section.name.lowercase()))
        }
        val reader = DefaultOpReader(this, section, path, map)
        reader.consume("id")
        reader.consume("op")
        val whenExpr = reader.boolValue("when")
        val op = decoder.decode(opName, id, whenExpr, reader)
        reader.failOnUnconsumed()
        return op
    }

    fun decodeOps(section: OpSection, raw: Any?, listPath: String): List<SkillOp> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: ValueDecoding.mismatch(skillId, listPath, "list of ops", raw)
        return list.mapIndexed { i, item -> decodeOp(section, item, listPath, i) }
    }
}

/**
 * [OpReader] over one YAML map. Every typed read marks its key consumed; [failOnUnconsumed] then reports the first
 * key nobody asked for as `UnknownKey`. Also used for the non-op maps of the skill tree (frames, rows, ...).
 */
internal class DefaultOpReader(
    private val ctx: OpDecodeContext,
    private val section: OpSection,
    override val path: String,
    private val map: Map<*, *>,
) : OpReader {
    private val consumed = HashSet<String>()
    private val skillId: String get() = ctx.skillId

    fun consume(key: String) {
        consumed += key
    }

    private fun take(key: String): Any? {
        consumed += key
        return map[key]
    }

    private fun at(key: String) = if (path.isEmpty()) key else "$path.$key"

    private val children = ArrayList<DefaultOpReader>()

    fun failOnUnconsumed() {
        children.forEach { it.failOnUnconsumed() }
        val extra = map.keys.map { it.toString() }.firstOrNull { it !in consumed } ?: return
        throw DecodeAbort(SkillDecodeError.UnknownKey(skillId, path, extra))
    }

    /** Declares [allowed] as the only keys this map may hold (for maps read through the typed accessors too). */
    fun allow(allowed: Set<String>) {
        val extra = map.keys.map { it.toString() }.firstOrNull { it !in allowed } ?: return
        throw DecodeAbort(SkillDecodeError.UnknownKey(skillId, path, extra))
    }

    override fun string(key: String): String? {
        val raw = take(key) ?: return null
        return raw as? String ?: ValueDecoding.mismatch(skillId, at(key), "string", raw)
    }

    override fun requireString(key: String): String =
        string(key) ?: throw DecodeAbort(SkillDecodeError.MissingField(skillId, path, key))

    override fun int(key: String): Int? {
        val raw = take(key) ?: return null
        val n = raw as? Number
        if (n == null || n.toDouble() != Math.rint(n.toDouble()) || n.toDouble().let { it > Int.MAX_VALUE || it < Int.MIN_VALUE }) {
            ValueDecoding.mismatch(skillId, at(key), "integer", raw)
        }
        return n.toInt()
    }

    override fun bool(key: String): Boolean? {
        val raw = take(key) ?: return null
        return raw as? Boolean ?: ValueDecoding.mismatch(skillId, at(key), "boolean", raw)
    }

    override fun number(key: String): Value<Double>? {
        val raw = take(key) ?: return null
        return ValueDecoding.number(raw, skillId, at(key))
    }

    override fun requireNumber(key: String): Value<Double> =
        number(key) ?: throw DecodeAbort(SkillDecodeError.MissingField(skillId, path, key))

    override fun vec3(key: String): Value<Vec3>? {
        val raw = take(key) ?: return null
        return ValueDecoding.vec3(raw, skillId, at(key))
    }

    override fun boolValue(key: String): Value<Boolean>? {
        val raw = take(key) ?: return null
        return ValueDecoding.bool(raw, skillId, at(key))
    }

    override fun textValue(key: String): Value<String>? {
        val raw = take(key) ?: return null
        return ValueDecoding.text(raw, skillId, at(key))
    }

    override fun point(key: String): PointRef? {
        val raw = take(key) ?: return null
        val s = raw as? String ?: ValueDecoding.mismatch(skillId, at(key), "point reference (frame.point)", raw)
        if (s.isBlank() || s.startsWith('.') || s.endsWith('.')) ValueDecoding.mismatch(skillId, at(key), "point reference (frame.point)", s)
        return PointRef.parse(s)
    }

    override fun stringList(key: String): List<String>? {
        val raw = take(key) ?: return null
        val list = raw as? List<*> ?: ValueDecoding.mismatch(skillId, at(key), "list of strings", raw)
        return list.mapIndexed { i, e -> e as? String ?: ValueDecoding.mismatch(skillId, "${at(key)}[$i]", "string", e) }
    }

    override fun ops(key: String): List<SkillOp> = ctx.decodeOps(section, take(key), at(key))

    override fun entries(key: String): List<OpReader> {
        val raw = take(key) ?: return emptyList()
        val list = raw as? List<*> ?: ValueDecoding.mismatch(skillId, at(key), "list", raw)
        return list.mapIndexed { i, item ->
            val m = item as? Map<*, *> ?: ValueDecoding.mismatch(skillId, "${at(key)}[$i]", "map", item)
            DefaultOpReader(ctx, section, "${at(key)}[$i]", m).also { children += it }
        }
    }

    override fun raw(key: String): Any? = take(key)

    override fun fail(message: String): Nothing =
        throw DecodeAbort(SkillDecodeError.Invalid(skillId, path, message))

    /** Raw value of [key] as a map, or null when absent. */
    fun mapOrNull(key: String): Map<*, *>? {
        val raw = take(key) ?: return null
        return raw as? Map<*, *> ?: ValueDecoding.mismatch(skillId, at(key), "map", raw)
    }

    /** Raw value of [key] as a list, or null when absent. */
    fun listOrNull(key: String): List<*>? {
        val raw = take(key) ?: return null
        return raw as? List<*> ?: ValueDecoding.mismatch(skillId, at(key), "list", raw)
    }

    fun literalDouble(key: String): Double? {
        val raw = take(key) ?: return null
        return (raw as? Number)?.toDouble() ?: ValueDecoding.mismatch(skillId, at(key), "number", raw)
    }

    fun literalVec3(key: String): Vec3? {
        val raw = take(key) ?: return null
        return ValueDecoding.literalVec3(raw, skillId, at(key))
    }

    fun missing(key: String): Nothing = throw DecodeAbort(SkillDecodeError.MissingField(skillId, path, key))
}
