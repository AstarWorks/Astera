package com.astarworks.astera.bots

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * What extra skills every item can cast, derived from the shipped content (`content/items/<id>.yaml` skill bindings and
 * the bound `content/skills/<id>/skill.yaml`), so no per-weapon list is kept in the bot. The tooltip a bot sees
 * (`[Shift+RMB] <skill name>`) carries neither the skill id nor its range / cooldown, hence the content read; the runner image
 * ships `/content` (docker/Dockerfile.bot-runner).
 *
 * An item is found by its display name (en or ja, contained in the hotbar name; the longest match wins). Its PRIMARY binding
 * is the brain's normal fire loop (`left_click` = melee swing; for an item without one, `right_click` = shot / cast) and is
 * left alone. `drop` / `sneak_drop` are the reload, `hold` / `shoot` are charge mechanics: also left alone. Every other
 * binding becomes a [SkillUsePolicy.SkillBinding] whose range comes from the skill's own data:
 *  - tags `support` / `heal` / `cover`: no damage to deal, never cast;
 *  - tags `movement` / `mobility` (dash, lunge, step): a gap closer 5..14 blocks for a melee item, an escape within 4.5 blocks
 *    for a ranged one;
 *  - `params.radius` (tag `aoe`): 0..radius + 0.5;  `params.range` (cone / sector): 0..range + 0.6;
 *  - tag `ranged`: 3..0.7 * speed * life (default 25, cap [MAX_RANGE]);  tag `melee` alone: 0..3.5.
 * Cooldown = the binding's `cooldown_override_ticks`, else the skill's `meta.cooldown_ticks`, at 50 ms per tick.
 * A binding no rule fits is listed in [unclassified] (and not cast); a game-sim scenario asserts the shipped pool has none.
 */
class ItemSkillCatalog private constructor(
    private val items: Map<String, Item>,
    /** `item:trigger:skill` of every extra binding no range rule fits. */
    val unclassified: List<String>,
) {
    /** [names] = lower-cased display names; [extras] = the castable bindings in file order. */
    class Item(val id: String, val names: List<String>, val primary: String, val extras: List<SkillUsePolicy.SkillBinding>)

    val itemIds: Set<String> get() = items.keys

    fun item(id: String): Item? = items[id]

    /** The item whose display name is contained in the hotbar [displayName] (longest match), or null. */
    fun findByDisplayName(displayName: String): Item? {
        val n = displayName.lowercase()
        if (n.isBlank()) return null
        return items.values.mapNotNull { item -> item.names.filter { it in n }.maxOfOrNull { it.length }?.let { item to it } }
            .maxByOrNull { it.second }?.first
    }

    /** A fresh policy (own cooldowns) for the item in a hotbar slot named [displayName]; empty when it has no extra skills. */
    fun policyFor(displayName: String): SkillUsePolicy = SkillUsePolicy(findByDisplayName(displayName)?.extras.orEmpty())

    companion object {
        const val MAX_RANGE = 40.0
        private const val TICK_MS = 50L
        private val NOT_EXTRA = setOf("drop", "sneak_drop", "hold", "shoot")

        private val cache = java.util.concurrent.ConcurrentHashMap<String, ItemSkillCatalog>()

        /** [load] once per content directory (every bot of the runner shares it). */
        fun cached(contentDir: File): ItemSkillCatalog = cache.computeIfAbsent(contentDir.absolutePath) { load(contentDir) }

        fun load(contentDir: File): ItemSkillCatalog {
            val yaml = Yaml()
            val skills = HashMap<String, Map<String, Any?>?>()
            fun skillOf(id: String): Map<String, Any?>? = skills.getOrPut(id) {
                File(contentDir, "skills/$id/skill.yaml").takeIf { it.isFile }?.let { yaml.load<Map<String, Any?>>(it.readText()) }
            }
            val items = LinkedHashMap<String, Item>()
            val unclassified = ArrayList<String>()
            (File(contentDir, "items").listFiles { f -> f.extension == "yaml" } ?: emptyArray()).sortedBy { it.name }.forEach { file ->
                val doc = yaml.load<Map<String, Any?>>(file.readText()) ?: return@forEach
                val id = doc["id"] as? String ?: return@forEach
                val bindings = (doc["skills"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
                if (bindings.isEmpty()) return@forEach
                val triggers = bindings.mapNotNull { it["trigger"] as? String }
                val primary = if ("left_click" in triggers) "left_click" else "right_click"
                val rangedItem = primary == "right_click"
                val extras = ArrayList<SkillUsePolicy.SkillBinding>()
                for (b in bindings) {
                    val trigger = b["trigger"] as? String ?: continue
                    val skillId = b["skill_id"] as? String ?: continue
                    if (trigger == primary || trigger in NOT_EXTRA) continue
                    val botTrigger = when (trigger) {
                        "sneak_right" -> SkillUsePolicy.Trigger.SNEAK_RIGHT
                        "right_click" -> SkillUsePolicy.Trigger.RIGHT_CLICK
                        "left_click" -> SkillUsePolicy.Trigger.LEFT_CLICK
                        else -> { unclassified += "$id:$trigger:$skillId"; continue }
                    }
                    val skill = skillOf(skillId)
                    if (skill == null) { unclassified += "$id:$trigger:$skillId"; continue }
                    val meta = skill["meta"] as? Map<*, *>
                    val tags = (meta?.get("tags") as? List<*>).orEmpty().map { it.toString() }.toSet()
                    if (tags.any { it in NEVER_CAST }) continue
                    val cooldownTicks = (b["cooldown_override_ticks"] as? Number ?: meta?.get("cooldown_ticks") as? Number)?.toLong() ?: 0L
                    val range = rangeOf(tags, skill["params"] as? Map<*, *>, rangedItem)
                    if (range == null) { unclassified += "$id:$trigger:$skillId"; continue }
                    extras += SkillUsePolicy.SkillBinding(skillId, botTrigger, range.first, range.second, cooldownTicks * TICK_MS)
                }
                val en = ((doc["name"] as? Map<*, *>)?.values.orEmpty()).map { it.toString().lowercase() }.filter { it.isNotBlank() }
                items[id] = Item(id, en, primary, extras)
            }
            return ItemSkillCatalog(items, unclassified)
        }

        private val NEVER_CAST = setOf("support", "heal", "cover")

        private fun paramDefault(params: Map<*, *>?, key: String): Double? =
            ((params?.get(key) as? Map<*, *>)?.get("default") as? Number)?.toDouble()

        /** (min, max) block distance to cast at, or null when no rule fits. */
        private fun rangeOf(tags: Set<String>, params: Map<*, *>?, rangedItem: Boolean): Pair<Double, Double>? {
            val radius = paramDefault(params, "radius")
            val range = paramDefault(params, "range")
            val speed = paramDefault(params, "speed")
            val life = paramDefault(params, "life")
            return when {
                ("movement" in tags || "mobility" in tags) && "aoe" !in tags ->
                    if (rangedItem) 0.0 to 4.5 else 5.0 to 14.0
                radius != null -> 0.0 to radius + 0.5
                range != null -> 0.0 to range + 0.6
                "ranged" in tags -> 3.0 to (if (speed != null && life != null) speed * life * 0.7 else 25.0).coerceAtMost(MAX_RANGE)
                "melee" in tags -> 0.0 to 3.5
                else -> null
            }
        }
    }
}
