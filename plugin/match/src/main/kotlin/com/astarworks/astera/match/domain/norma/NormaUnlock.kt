package com.astarworks.astera.match.domain.norma

import com.astarworks.astera.core.domain.item.Rarity

/** One weapon rarity a Norma level opens up for a player (ADR-0292): weapons of [rarity] are usable from [level]. */
public data class NormaUnlock(val level: Int, val rarity: Rarity)
