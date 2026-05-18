package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Small-scale arena mode: first team to [scoreThreshold] points wins (Wave 5c).
 *
 * Victory evaluation reuses the existing
 * [com.astarworks.astera.match.application.usecase.match.VictoryConditionEvaluator]
 * (score-threshold path, ADR-0027). The mode therefore needs no dedicated
 * tick loop — its listener exists for symmetry with other modes and to
 * surface lifecycle hooks the HUD may want later.
 *
 * @property scoreThreshold Points needed to win. Defaults to 10, overridable
 *   per stage YAML.
 */
public data class BattleLoyal(
    override val id: GameModeId = GameModeId("battle-loyal"),
    override val displayNameKey: MessageKey = MessageKey("astera.match.mode.battle-loyal.name"),
    override val descriptionKey: MessageKey = MessageKey("astera.match.mode.battle-loyal.description"),
    val scoreThreshold: Int = 10,
) : GameMode() {
    init {
        require(scoreThreshold > 0) { "scoreThreshold must be > 0, got $scoreThreshold" }
    }
}
