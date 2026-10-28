package com.astarworks.astera.skilllint

/**
 * Rules that read only the composed generic tree, so they still run when the typed decode fails
 * (a gameplay op in `presentation` is a decode error too; lint names it with this rule first).
 */
object TreeRules {
    /** Op names that move frames, hit, damage or raise events: forbidden in `presentation`. */
    private val GAMEPLAY_ONLY_OPS = setOf(
        "pose", "spin", "fly", "path", "follow", "stick", "reel", "reparent", "place", "hit", "projectile", "aoe",
        "summon", "summon_lightning", "damage", "heal", "resurrect", "explode", "status", "remove_status", "cleanse",
        "ignite", "silence", "modify_attribute", "restore_resource", "impulse", "knockback", "knockup", "set_velocity",
        "propel", "teleport", "leap", "dash_strike", "set_block", "remove_block", "break_area", "place_shape",
        "freeze", "schedule", "event", "cast_skill", "if", "loop", "choose", "weighted_branch", "for_each", "counter",
        "set_cooldown", "give_item", "consume_item", "window",
    )

    @Suppress("UNCHECKED_CAST")
    fun presentationMovesFrames(skillId: String, tree: Map<String, Any?>): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        val section = tree["presentation"] as? Map<String, Any?> ?: return out
        fun scan(items: Any?, base: String) {
            for (item in items as? List<*> ?: return) {
                val m = item as? Map<String, Any?> ?: continue
                val op = m["op"] as? String ?: continue
                if (op in GAMEPLAY_ONLY_OPS) {
                    out += LintFinding(
                        "presentation-moves-frames", Severity.FAIL, skillId, "$base[${m["id"] ?: op}]",
                        "op '$op' belongs to gameplay; presentation may only attach to frames and points",
                    )
                }
            }
        }
        (section["timeline"] as? List<*>)?.forEachIndexed { i, row -> scan((row as? Map<String, Any?>)?.get("do"), "presentation.timeline[$i].do") }
        (section["on"] as? Map<String, Any?>)?.forEach { (event, items) -> scan(items, "presentation.on.$event") }
        return out
    }
}
