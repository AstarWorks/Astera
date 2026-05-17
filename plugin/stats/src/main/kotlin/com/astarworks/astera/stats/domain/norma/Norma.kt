package com.astarworks.astera.stats.domain.norma

import com.astarworks.astera.core.domain.i18n.MessageKey

public data class Norma(
    val id: NormaId,
    val target: NormaTarget,
    val reward: NormaReward,
    val period: PeriodKind,
    val displayKey: MessageKey,
)
