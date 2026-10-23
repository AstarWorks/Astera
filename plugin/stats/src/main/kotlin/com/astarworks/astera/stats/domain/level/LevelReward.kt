package com.astarworks.astera.stats.domain.level

public data class LevelReward(
    val gold: Long = 0,
    val titles: List<String> = emptyList(),
    val cosmetics: List<String> = emptyList(),
    val items: List<String> = emptyList(),
) {
    init {
        require(gold >= 0)
        require((titles + cosmetics + items).all { it.matches(Regex("[a-z][a-z0-9-]*")) })
    }
    public val unlocks: Set<String> get() =
        (titles.map { "title:$it" } + cosmetics.map { "cosmetic:$it" } + items.map { "item:$it" }).toSet()
}
