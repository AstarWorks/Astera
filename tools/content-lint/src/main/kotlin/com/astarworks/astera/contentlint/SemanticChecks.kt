package com.astarworks.astera.contentlint

import com.fasterxml.jackson.databind.JsonNode

/**
 * Checks the JSON Schema cannot express: content that *decodes* fine but that
 * the runtime (`MechanicDispatcher`, `StubCelEvaluator`) cannot execute, and
 * cross-file id references. Applied to `content/skills/` and `content/items/`.
 *
 * Goal: broken content fails loudly here instead of silently doing nothing in
 * game. The no-op sets mirror `combat/.../MechanicSupport.kt` (the Kotlin side
 * used by `/astera-validate`); keep the two in sync.
 */
internal object SemanticChecks {

    /** Mechanic `type:` values whose dispatcher branch only logs. */
    val NO_OP_MECHANICS: Set<String> = setOf(
        "heal", "resurrect", "remove_status", "cleanse", "ignite", "freeze", "silence",
        "set_velocity", "propel", "leap", "summon_lightning", "summon", "set_block",
        "remove_block", "explode", "give_item", "consume_item", "modify_attribute",
        "set_cooldown", "spawn_display", "schedule_particle_animation", "glow",
        "camera_shake", "send_message", "send_action_bar", "send_title",
    )

    /** Condition `type:` values that always evaluate to false at runtime. */
    val UNSUPPORTED_CONDITIONS: Set<String> = setOf(
        "has_status", "is_ally", "is_enemy", "health_below", "distance_less_than",
    )

    /** Targeter `type:` values that always resolve to no targets. */
    val UNSUPPORTED_TARGETERS: Set<String> = setOf("forward_ray", "cone", "enemies", "allies")

    /** Ids defined elsewhere in content/, for reference checks. */
    data class KnownIds(
        val skills: Set<String>,
        val statuses: Set<String>,
        val particles: Set<String>,
    )

    fun check(root: JsonNode, known: KnownIds): List<String> {
        val errors = mutableListOf<String>()
        walk(root, "$", known, errors)
        return errors
    }

    private fun walk(node: JsonNode, path: String, known: KnownIds, errors: MutableList<String>) {
        when {
            node.isObject -> {
                val type = node.get("type")?.takeIf { it.isTextual }?.asText()
                if (type != null) classify(type, path, errors)
                checkRef(node, "status_id", known.statuses, "status", path, errors)
                checkRef(node, "status_effect_id", known.statuses, "status", path, errors)
                checkRef(node, "particle_id", known.particles, "particle", path, errors)
                checkRef(node, "skill_id", known.skills, "skill", path, errors)
                for ((key, child) in node.properties()) {
                    // `params.type` is the archetype discriminator (projectile,
                    // summon, teleport, ...), not a mechanic -- skip its own type.
                    if (key == "params") {
                        walkParams(child, "$path.$key", known, errors)
                    } else {
                        walk(child, "$path.$key", known, errors)
                    }
                }
            }
            node.isArray -> node.forEachIndexed { i, child -> walk(child, "$path[$i]", known, errors) }
            else -> Unit
        }
    }

    /** Walk a `params` block without treating its `type` as a mechanic. */
    private fun walkParams(params: JsonNode, path: String, known: KnownIds, errors: MutableList<String>) {
        if (!params.isObject) return
        checkRef(params, "status_effect_id", known.statuses, "status", path, errors)
        for ((key, child) in params.properties()) {
            if (key != "type") walk(child, "$path.$key", known, errors)
        }
    }

    private fun classify(type: String, path: String, errors: MutableList<String>) {
        when (type) {
            "cel" -> errors += "$path: CEL scripts are not supported yet (StubCelEvaluator evaluates to 0/false/ZERO)"
            in NO_OP_MECHANICS -> errors += "$path: mechanic '$type' is not implemented (no-op at runtime)"
            in UNSUPPORTED_CONDITIONS -> errors += "$path: condition '$type' always evaluates to false at runtime"
            in UNSUPPORTED_TARGETERS -> errors += "$path: targeter '$type' always resolves to no targets at runtime"
        }
    }

    private fun checkRef(
        node: JsonNode,
        field: String,
        known: Set<String>,
        what: String,
        path: String,
        errors: MutableList<String>,
    ) {
        val value = node.get(field)?.takeIf { it.isTextual }?.asText() ?: return
        if (value !in known) errors += "$path.$field: unknown $what id '$value'"
    }
}
