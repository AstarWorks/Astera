package com.astarworks.astera.contentlint

import com.fasterxml.jackson.databind.JsonNode

/**
 * Checks the JSON Schema cannot express, for content that is not a skill. Skill documents (astera/v2) are
 * validated by [SkillV2Lint]; their composition / decode / reference checks live in skill-lint.
 */
internal object SemanticChecks {

    /** Max tooltip line width in px (full-width 8, other 6): docs/contributing/item-lore-style.md section 5. */
    private const val MAX_LINE_PX = 256
    private val LANGS = listOf("ja", "en")

    /**
     * Item lore notation (docs/contributing/item-lore-style.md): gear carries `role` (+ optional `flavor`) in
     * both languages, plain text and one line each; skill / stat lines are generated, so gear has no `lore:`.
     */
    fun checkItemText(root: JsonNode): List<String> {
        val errors = mutableListOf<String>()
        val gear = listOf("skills", "attributes", "tool", "armor_piece", "resource_type").any { root.has(it) }
        val plain = root.has("lore")
        if (gear && plain) errors += "item-lore: gear must not use `lore:` (use `role:` / `flavor:`; skills and stats are generated)"
        val id = root.get("id")?.asText().orEmpty()
        val panel = id.startsWith("gui-") || id.startsWith("indicator-")
        if (!plain && !panel && !root.has("role")) errors += "item-lore: `role:` (one-line summary, ja + en) is required"
        for (field in listOf("role", "flavor")) {
            val node = root.get(field) ?: continue
            for (lang in LANGS) {
                val text = node.get(lang)?.takeIf { it.isTextual }?.asText()
                if (text == null) {
                    errors += "item-lore: `$field` is missing `$lang`"
                    continue
                }
                if (text.contains('<') || text.contains('&')) errors += "item-lore: `$field.$lang` must be plain text (no colour / MiniMessage tags)"
                if (text.contains('\n')) errors += "item-lore: `$field.$lang` must be a single line"
                val px = text.sumOf { if (it.code >= 0x2E80 && it.code !in 0xFF61..0xFF9F) 8 else 6 }
                if (px > MAX_LINE_PX) errors += "item-lore: `$field.$lang` is ~${px}px wide (limit $MAX_LINE_PX, about 32 full-width / 40 half-width chars)"
            }
        }
        return errors
    }

    /** Distinct `skill_id` values an item binds (advisory: they must exist as `kind: Skill` documents once ported). */
    fun itemSkillIds(root: JsonNode): Set<String> {
        val out = linkedSetOf<String>()
        fun walk(n: JsonNode) {
            if (n.isObject) {
                n.get("skill_id")?.takeIf { it.isTextual }?.asText()?.let { out += it }
                n.properties().forEach { walk(it.value) }
            } else if (n.isArray) n.forEach { walk(it) }
        }
        walk(root)
        return out
    }
}
