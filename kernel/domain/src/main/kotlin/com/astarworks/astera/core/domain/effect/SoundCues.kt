package com.astarworks.astera.core.domain.effect

/**
 * Ids of the sound cues played at game milestones. Each id is defined by a YAML file under
 * `content/sounds/` (ADR-0267); code names a cue, never a raw sound key, so the sound behind a
 * milestone can be swapped (vanilla now, own OGG later) without touching a listener.
 */
public object SoundCues {
    public val MATCH_COUNTDOWN_TICK: SoundId = SoundId("astera:match/countdown-tick")
    public val MATCH_GO: SoundId = SoundId("astera:match/go")
    public val MATCH_DEATH: SoundId = SoundId("astera:match/death")
    public val MATCH_RESPAWN: SoundId = SoundId("astera:match/respawn")
    public val MATCH_WIN: SoundId = SoundId("astera:match/win")
    public val MATCH_LOSE: SoundId = SoundId("astera:match/lose")
    public val MATCH_DRAW: SoundId = SoundId("astera:match/draw")
    public val MATCH_SUDDEN_DEATH: SoundId = SoundId("astera:match/sudden-death")

    public val COMBAT_HIT: SoundId = SoundId("astera:combat/hit")
    public val COMBAT_CRIT: SoundId = SoundId("astera:combat/crit")
    public val COMBAT_BLOCKED: SoundId = SoundId("astera:combat/blocked")
    public val COMBAT_KILL: SoundId = SoundId("astera:combat/kill")
    public val COMBAT_EGO_READY: SoundId = SoundId("astera:combat/ego-ready")
    public val COMBAT_SKILL_READY: SoundId = SoundId("astera:combat/skill-ready")

    public val STAR_PICKUP: SoundId = SoundId("astera:star/pickup")
    public val STAR_BURST: SoundId = SoundId("astera:star/burst")
    public val STAR_POP: SoundId = SoundId("astera:star/pop")
    public val STAR_BURST_GAIN: SoundId = SoundId("astera:star/burst-gain")

    public val NORMA_LEVEL_UP: SoundId = SoundId("astera:norma/level-up")
    public val NORMA_LEVEL_MAX: SoundId = SoundId("astera:norma/level-max")
    public val NORMA_BURST: SoundId = SoundId("astera:norma/burst")
    public val NORMA_BURST_MAX: SoundId = SoundId("astera:norma/burst-max")
    public val NORMA_CHOICE_OPEN: SoundId = SoundId("astera:norma/choice-open")
    public val NORMA_CHOICE_PICKED: SoundId = SoundId("astera:norma/choice-picked")
    public val NORMA_CHOICE_DEFAULTED: SoundId = SoundId("astera:norma/choice-defaulted")

    public val UI_LEVEL_UP: SoundId = SoundId("astera:ui/level-up")
    public val UI_LOGIN_BONUS: SoundId = SoundId("astera:ui/login-bonus")
    public val UI_QUEST_COMPLETE: SoundId = SoundId("astera:ui/quest-complete")
    public val UI_PURCHASE: SoundId = SoundId("astera:ui/purchase")
}
