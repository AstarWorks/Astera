package com.astarworks.astera.contentlint

import com.fasterxml.jackson.databind.JsonNode

/**
 * Checks the JSON Schema cannot express: content that *decodes* fine but that
 * the runtime (`MechanicDispatcher`, `StubCelEvaluator`) cannot execute, and
 * cross-file id references. Applied to `content/skills/`, `content/items/` and `content/vfx/`.
 *
 * Goal: broken content fails loudly here instead of silently doing nothing in
 * game. The no-op sets mirror `combat/.../MechanicSupport.kt` (the Kotlin side
 * used by `/astera admin validate`); keep the two in sync.
 */
internal object SemanticChecks {

    /**
     * Mechanic `type:` values whose dispatcher branch only logs. WP4 batch A
     * (cleanse/ignite/freeze/silence, set_velocity/propel/leap, send_*,
     * set_cooldown, modify_attribute, remove_status) and WP5 batch B (heal, explode,
     * summon_lightning, summon, set_block, remove_block, spawn_display, glow,
     * camera_shake) are implemented.
     */
    val NO_OP_MECHANICS: Set<String> = setOf(
        "resurrect", "give_item", "consume_item",
    )

    /** Condition `type:` values that always evaluate to false at runtime (none: all entity-state conditions are implemented). */
    val UNSUPPORTED_CONDITIONS: Set<String> = emptySet()

    /** Targeter `type:` values that always resolve to no targets (none: ForwardRay / Cone / Enemies / Allies landed with ADR-0174). */
    val UNSUPPORTED_TARGETERS: Set<String> = emptySet()

    /** Ids defined elsewhere in content/, for reference checks. */
    data class KnownIds(
        val skills: Set<String>,
        val statuses: Set<String>,
        val particles: Set<String>,
        val vfx: Set<String> = emptySet(),
        val sprites: Set<String> = emptySet(),
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
                checkRef(node, "vfx_id", known.vfx, "vfx", path, errors)
                // ADR-0237: only a vfx element kind (`type: sprite`) carries a sprite reference.
                if (type == "sprite") checkRef(node, "sprite", known.sprites, "sprite", path, errors)
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
