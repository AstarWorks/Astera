package com.astarworks.astera.match.application.port.outbound

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
     * [unlocked] is what this level opens for [player] (named in the subtitle and a private chat line).
     */
    public fun levelUp(player: PlayerId, audience: List<PlayerId>, level: Int, maxLevel: Int, unlocked: List<NormaUnlock>)

    /** [player] tried to use something that unlocks at Norma level [required]: deny sound and an action bar line. */
    public fun locked(player: PlayerId, required: Int)

    /** The goal picker just opened for [player]: sound cue only (the GUI covers any title). */
    public fun choiceOpened(player: PlayerId)

    /** [player] picked [goal] (needing [target] stars or kills): confirmation sound and action bar. */
    public fun choicePicked(player: PlayerId, goal: NormaGoal, target: Int)

    /** The 10 s window ran out without a pick: the default [goal] stands (needing [target]). */
    public fun choiceDefaulted(player: PlayerId, goal: NormaGoal, target: Int)
}
