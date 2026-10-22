package com.astarworks.astera.stats.application.config

import com.astarworks.astera.stats.domain.level.LevelReward
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
public data class LevelRewardsYamlConfig(val levels: Map<Int, LevelRewardYamlConfig>) {
    init { require(levels.keys.all { it >= 2 }) }
}

@Serializable
public data class LevelRewardYamlConfig(
    val star: Long = 0,
    val gold: Long = 0,
    val titles: List<String> = emptyList(),
    val cosmetics: List<String> = emptyList(),
    val items: List<String> = emptyList(),
) {
    public fun toDomain(): LevelReward = LevelReward(star, gold, titles, cosmetics, items)
}

@Serializable
public data class SeasonsYamlConfig(val seasons: List<SeasonWindowYamlConfig>) {
    init {
        require(seasons.map { it.id }.distinct().size == seasons.size)
        seasons.forEach { it.validate() }
        require(seasons.zipWithNext().all { (a, b) -> !Instant.parse(a.end).isAfter(Instant.parse(b.start)) })
    }
}

@Serializable
public data class SeasonWindowYamlConfig(val id: String, val start: String, val end: String, val next: String) {
    public fun validate() {
        require(id.matches(Regex("[a-z][a-z0-9-]*")))
        require(next.isNotBlank())
        require(Instant.parse(start).isBefore(Instant.parse(end)))
    }
}
