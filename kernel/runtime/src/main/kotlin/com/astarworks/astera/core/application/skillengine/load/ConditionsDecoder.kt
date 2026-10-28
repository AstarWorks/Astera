package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.expr.cel.CondBindings
import com.astarworks.astera.core.domain.Result

/** Decodes the top-level `conditions:` map of a skill or part (ADR-0303 3.8): name -> CEL source, strict. */
public object ConditionsDecoder {
    public fun decode(skillId: String, node: YamlNode, path: String = "conditions"): Result<Map<String, String>, SkillDecodeError> {
        if (node == null) return Result.Success(emptyMap())
        val map = node as? Map<*, *>
            ?: return Result.Failure(SkillDecodeError.TypeMismatch(skillId, path, "map of name -> expression", describe(node)))
        val out = LinkedHashMap<String, String>()
        for ((k, v) in map) {
            val name = k as? String
                ?: return Result.Failure(SkillDecodeError.TypeMismatch(skillId, path, "string key", describe(k)))
            val at = "$path.$name"
            if (!CondBindings.isValidName(name)) {
                return Result.Failure(SkillDecodeError.Invalid(skillId, at, "condition name '$name' is not an identifier"))
            }
            val body = v as? String
                ?: return Result.Failure(SkillDecodeError.TypeMismatch(skillId, at, "CEL expression string", describe(v)))
            if (body.trim().removePrefix("=").isBlank()) {
                return Result.Failure(SkillDecodeError.Invalid(skillId, at, "condition '$name' has an empty body"))
            }
            out[name] = body
        }
        return Result.Success(out)
    }

    private fun describe(v: Any?): String = if (v == null) "null" else v::class.simpleName ?: "value"
}
