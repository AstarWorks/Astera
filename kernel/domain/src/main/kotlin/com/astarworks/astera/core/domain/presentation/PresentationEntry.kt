package com.astarworks.astera.core.domain.presentation

import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.i18n.MessageKey

/** A named particle burst: a Paper particle type name plus count / spread / speed (resolved by the Paper adapter). */
public data class ParticlePreset(
    public val id: String,
    public val particle: String,
    public val count: Int = 1,
    public val spreadX: Double = 0.0,
    public val spreadY: Double = 0.0,
    public val spreadZ: Double = 0.0,
    public val speed: Double = 0.0,
    /** Blocks above the anchor (the subject's feet or the given position) where the burst is centred. */
    public val yOffset: Double = 1.0,
) {
    init {
        require(id.isNotBlank()) { "particle preset id must not be blank" }
        require(particle.isNotBlank()) { "particle type must not be blank" }
        require(count >= 0) { "particle count must be >= 0, got $count" }
    }
}

/**
 * What one game moment looks and sounds like (`content/presentation/events.yaml`): the subject's [cue], an optional
 * [audienceCue] for everyone who should hear it, title / subtitle / action bar message keys, a [particle] preset id,
 * and an optional pitch chain (consecutive plays of this moment within [chainWindowMillis] rise by
 * [chainPitchSemitones] each, up to [chainMaxSteps] steps).
 */
public data class PresentationEntry(
    public val moment: String,
    public val cue: SoundId? = null,
    public val audienceCue: SoundId? = null,
    public val title: MessageKey? = null,
    public val subtitle: MessageKey? = null,
    /** Action bar message key; `{name}` placeholders are filled from the play args (e.g. `picked.{kind}`). */
    public val actionBar: String? = null,
    public val titleStayTicks: Int = 40,
    public val particle: String? = null,
    public val chainWindowMillis: Long = 0L,
    public val chainPitchSemitones: Double = 0.0,
    public val chainMaxSteps: Int = 0,
) {
    init {
        require(moment.isNotBlank()) { "moment id must not be blank" }
        require(titleStayTicks >= 0) { "titleStayTicks must be >= 0" }
        require(chainMaxSteps >= 0) { "chainMaxSteps must be >= 0" }
    }
}

/** Ids of the game moments that must each have a row in `content/presentation/events.yaml`. */
public object PresentationMoments {
    public const val COUNTDOWN_TICK: String = "countdown-tick"
    public const val COUNTDOWN_GO: String = "countdown-go"
    public const val KILL: String = "kill"
    public const val DEATH: String = "death"
    public const val RESPAWN: String = "respawn"
    public const val HIT: String = "hit"
    public const val CRIT: String = "crit"
    public const val BLOCKED: String = "blocked"
    public const val SKILL_READY: String = "skill-ready"
    public const val EGO_READY: String = "ego-ready"
    public const val STAR_POP: String = "star-pop"
    public const val STAR_PICKUP: String = "star-pickup"
    public const val STAR_BURST: String = "star-burst"
    public const val STAR_BURST_KILLER: String = "star-burst-killer"
    public const val NORMA_LEVEL_UP: String = "norma-level-up"
    public const val NORMA_LEVEL_MAX: String = "norma-level-max"
    public const val NORMA_CHOICE_OPEN: String = "norma-choice-open"
    public const val NORMA_CHOICE_PICKED: String = "norma-choice-picked"
    public const val NORMA_CHOICE_DEFAULTED: String = "norma-choice-defaulted"
    public const val MATCH_WIN: String = "match-win"
    public const val MATCH_LOSE: String = "match-lose"
    public const val MATCH_DRAW: String = "match-draw"
    public const val REWARD_LOGIN: String = "reward-login"
    public const val PURCHASE: String = "purchase"
    public const val QUEST_COMPLETE: String = "quest-complete"
    public const val PLAYER_LEVEL_UP: String = "player-level-up"
    public const val UI_CLICK: String = "ui-click"
    public const val UI_CONFIRM: String = "ui-confirm"
    public const val UI_DENY: String = "ui-deny"

    public val ALL: List<String> = listOf(
        COUNTDOWN_TICK, COUNTDOWN_GO, KILL, DEATH, RESPAWN, HIT, CRIT, BLOCKED, SKILL_READY, EGO_READY,
        STAR_POP, STAR_PICKUP, STAR_BURST, STAR_BURST_KILLER,
        NORMA_LEVEL_UP, NORMA_LEVEL_MAX, NORMA_CHOICE_OPEN, NORMA_CHOICE_PICKED, NORMA_CHOICE_DEFAULTED,
        MATCH_WIN, MATCH_LOSE, MATCH_DRAW, REWARD_LOGIN, PURCHASE, QUEST_COMPLETE, PLAYER_LEVEL_UP,
        UI_CLICK, UI_CONFIRM, UI_DENY,
    )
}
