package com.astarworks.astera.match.domain.norma

import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.skill.SkillTrigger

/** One item action a Norma level opens up for a player (ADR-0273): the item's name and the input that fires it. */
public data class NormaUnlock(val level: Int, val item: LocalizedText, val trigger: SkillTrigger)
