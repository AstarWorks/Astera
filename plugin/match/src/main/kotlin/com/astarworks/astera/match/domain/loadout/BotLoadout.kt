package com.astarworks.astera.match.domain.loadout

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.WeaponCategory
import kotlin.random.Random

/** The weapon families a bot loadout is drawn from: a bot carries two weapons of two different families. */
public enum class BotWeaponFamily(public val key: String) {
    /** Guns and bows. */
    RANGED("ranged"),

    /** Swords, axes, hammers and other close-range weapons. */
    MELEE("melee"),

    /** Staves and other casters. */
    CASTER("caster"),
}

/** One eligible pool entry: [category] is the weapon-limit family of the item ([WeaponCategory.of]). */
public data class BotWeapon(val item: ItemId, val family: BotWeaponFamily, val category: WeaponCategory)

/** The two weapons of one bot. */
public data class BotLoadout(val first: BotWeapon, val second: BotWeapon) {
    public val items: List<ItemId> get() = listOf(first.item, second.item)
    public val key: Set<ItemId> get() = setOf(first.item, second.item)
}

/**
 * Draws bot loadouts. A loadout is two weapons of two different [BotWeaponFamily]s that also differ in
 * [WeaponCategory], so it always fits the match weapon limit (`gun:1,sword:1,staff:1,total:2`). Family combinations are
 * weighted equally whatever their item counts, then an item pair is drawn inside the combination; a pair already
 * handed to another bot of the match is skipped until every pair was used once.
 */
public object BotLoadoutPicker {

    /** Every valid pair of [weapons], in a stable order (so a seeded draw is reproducible). */
    public fun pairs(weapons: List<BotWeapon>): List<BotLoadout> {
        val sorted = weapons.distinctBy { it.item }.sortedBy { it.item.value }
        val out = ArrayList<BotLoadout>()
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                val a = sorted[i]
                val b = sorted[j]
                if (a.family != b.family && a.category != b.category) out += BotLoadout(a, b)
            }
        }
        return out
    }

    private fun comboKey(pair: BotLoadout): String = listOf(pair.first.family.ordinal, pair.second.family.ordinal).sorted().joinToString("-")

    /**
     * @return a loadout from [weapons] not in [used], or any pair once all were used; `null` when no pair exists. Among the family
     * combinations the one [taken] (the loadouts the match already handed out) used least wins, so ranged and melee bots mix across
     * a team instead of every draw landing on the same combination; the item pair inside the combination is the seeded random draw.
     */
    public fun pick(weapons: List<BotWeapon>, used: Set<Set<ItemId>>, random: Random, taken: Collection<BotLoadout> = emptyList()): BotLoadout? {
        val all = pairs(weapons)
        if (all.isEmpty()) return null
        val fresh = all.filter { it.key !in used }.ifEmpty { all }
        val usage = taken.groupingBy { comboKey(it) }.eachCount()
        val groups = fresh.groupBy { comboKey(it) }.entries.sortedBy { it.key }
        val least = groups.minOf { usage[it.key] ?: 0 }
        val candidates = groups.filter { (usage[it.key] ?: 0) == least }.map { it.value }
        val group = candidates[random.nextInt(candidates.size)]
        return group[random.nextInt(group.size)]
    }
}
