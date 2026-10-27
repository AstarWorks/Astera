package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.norma.NormaGoal
import com.astarworks.astera.match.domain.norma.NormaUnlock

/**
 * What players see and hear around Norma levels (Paper: titles, sounds, particles, a firework, chat). Pure
 * presentation: it never changes match state. Strings are i18n keys resolved by the host per viewer.
 */
public interface INormaPresentation {
    /**
     * [player] cleared their Norma goal and now stands at [level] of [maxLevel]: title, sound and a burst at the
     * player, plus a chat line to [audience] (the match's players). Reaching [maxLevel] gets a bigger version.
     * [unlocked] is the weapon rarities this level opens (named in the subtitle and a private chat line).
     */
    public fun levelUp(player: PlayerId, audience: List<PlayerId>, level: Int, maxLevel: Int, unlocked: List<NormaUnlock>)

    /** [player] tried to use a weapon of [rarity], which opens at Norma level [required]: deny sound and an action bar line. */
    public fun locked(player: PlayerId, required: Int, rarity: Rarity)
}
