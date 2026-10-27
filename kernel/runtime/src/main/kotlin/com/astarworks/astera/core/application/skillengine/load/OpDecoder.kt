package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

/**
 * Typed access to one op's YAML map. Reading marks keys as consumed; the decoder reports any
 * unconsumed key as UnknownKey after [OpDecoder.decode] returns.
 */
public interface OpReader {
    public val path: String
    public fun string(key: String): String?
    public fun requireString(key: String): String
    public fun int(key: String): Int?
    public fun bool(key: String): Boolean?
    public fun number(key: String): Value<Double>?
    public fun requireNumber(key: String): Value<Double>
    public fun vec3(key: String): Value<Vec3>?
    public fun boolValue(key: String): Value<Boolean>?

    /** A string that may also be a `${param}` or `= expr` (e.g. colors); a plain string is a literal. */
    public fun textValue(key: String): Value<String>?
    public fun point(key: String): PointRef?
    public fun stringList(key: String): List<String>?

    /** Nested op lists (`on_hit:`), decoded in the same section through the registry. */
    public fun ops(key: String): List<SkillOp>

    /** Readers for the map entries of the list at [key] (`cases:`, `branches:`), each with its own consumed-key tracking. */
    public fun entries(key: String): List<OpReader>
    public fun raw(key: String): Any?
    public fun fail(message: String): Nothing
}

/** Decodes the ops of ONE family. Register in [OpDecoderRegistry]; no central `when`. */
public interface OpDecoder {
    public val section: OpSection

    /** Discriminator values (`op: dash_strike`) this decoder owns. */
    public val opNames: Set<String>
    public fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp
}

public interface OpDecoderRegistry {
    public fun register(decoder: OpDecoder)
    public fun find(section: OpSection, op: String): OpDecoder?
}
