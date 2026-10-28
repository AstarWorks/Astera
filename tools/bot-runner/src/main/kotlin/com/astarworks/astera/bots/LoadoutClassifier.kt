package com.astarworks.astera.bots

/**
 * What a hotbar item is for a bot, decided from the tooltip the server shows (lore trigger lines `[LMB] ...` / `[RMB] ...`,
 * en and ja) and the display name - never from "the lore mentions left-click". Pure and deterministic so a game-sim
 * scenario drives the very classification the live [BotBrain] uses.
 *
 *  - an `[LMB]` skill binding = a melee weapon;
 *  - only `[RMB]` / `[Hold RMB]` bindings = a ranged weapon (gun, staff, bow); a "reload" line marks a magazine gun;
 *  - a pickaxe / shovel / axe without an `[LMB]` binding = a [Kind.TOOL] with its [Affinity] (right-click tool skill, digs pillars);
 *  - the navigation compass, blocks, ores and anything else = [Kind.OTHER], never selected as a weapon.
 */
object LoadoutClassifier {
    enum class Kind { MELEE, RANGED, TOOL, OTHER }

    /** Tool class a pillar is dug with (`StagePillar.affinity`). */
    enum class Affinity { PICKAXE, SHOVEL, AXE }

    data class Item(val kind: Kind, val magazine: Boolean = false, val affinity: Affinity? = null)

    private val LMB = Regex("\\[lmb]|\\[左]|left-click|左クリック")
    private val RMB = Regex("\\[rmb]|\\[hold rmb]|\\[右]|\\[右長押し]|right-click|右クリック")
    private val RELOAD = Regex("reload|リロード")
    private val COMPASS = Regex("compass|コンパス")
    private val PICKAXE = Regex("pickaxe|ツルハシ")
    private val SHOVEL = Regex("shovel|ショベル|シャベル")
    private val AXE = Regex("\\baxe\\b|[-_ ]axe|オノ|斧")

    /** [name] is the display name, [lore] the tooltip lines joined by " | " (either may be empty). */
    fun classify(name: String, lore: String): Item {
        val n = name.lowercase()
        val l = lore.lowercase()
        val lmb = LMB.containsMatchIn(l)
        val rmb = RMB.containsMatchIn(l)
        val affinity = when {
            PICKAXE.containsMatchIn(n) -> Affinity.PICKAXE
            SHOVEL.containsMatchIn(n) -> Affinity.SHOVEL
            AXE.containsMatchIn(n) -> Affinity.AXE
            else -> null
        }
        return when {
            COMPASS.containsMatchIn(n) -> Item(Kind.OTHER)
            affinity != null && !lmb -> Item(Kind.TOOL, affinity = affinity)
            lmb -> Item(Kind.MELEE)
            rmb -> Item(Kind.RANGED, magazine = RELOAD.containsMatchIn(l))
            else -> Item(Kind.OTHER)
        }
    }

    /** The affinity a pillar of [tier] is dug with when the stage does not declare one (tier 1 axe, 2 shovel, 3 pickaxe). */
    fun defaultAffinity(tier: Int): Affinity = when (tier) { 1 -> Affinity.AXE; 2 -> Affinity.SHOVEL; else -> Affinity.PICKAXE }

    fun parseAffinity(raw: String?): Affinity? = raw?.let { r -> Affinity.entries.firstOrNull { it.name.equals(r, ignoreCase = true) } }
}
