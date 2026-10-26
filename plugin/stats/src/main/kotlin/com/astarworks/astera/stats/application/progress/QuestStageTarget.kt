package com.astarworks.astera.stats.application.progress

import com.astarworks.astera.core.domain.advancement.AdvancementCriteria

/** The scalar a quest stage counts towards; mirrors the quest service (least-progressed leaf of AllOf, most-progressed of AnyOf). */
internal fun questStageTarget(criteria: AdvancementCriteria): Int = when (criteria) {
    is AdvancementCriteria.KillCount -> criteria.target
    is AdvancementCriteria.CurrencyEarned -> criteria.amount.toInt().coerceAtLeast(1)
    is AdvancementCriteria.MatchesWon -> criteria.count
    is AdvancementCriteria.ItemAcquired -> 1
    is AdvancementCriteria.AllOf -> criteria.children.maxOf { questStageTarget(it) }
    is AdvancementCriteria.AnyOf -> criteria.children.minOf { questStageTarget(it) }
}
