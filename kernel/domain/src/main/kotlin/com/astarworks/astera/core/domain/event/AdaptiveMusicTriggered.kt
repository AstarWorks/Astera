package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicTrackId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5cinematic, ADR-0110): the adaptive BGM service
 * triggered a mood switch.
 *
 * Distinct from [MatchMusicChanged] — that event records *what* changed
 * (track A → track B); this event records *why* the adaptive service
 * decided to change (mood crossed, HP threshold hit). HUD / audit / analytics
 * subscribers consume both: `MatchMusicChanged` to update UI, this event to
 * record the trigger reason.
 *
 * Both fire in the same tick if the adaptive trigger leads to an actual
 * track change (the adaptive service publishes this, then forwards to the
 * per-match service which publishes `MatchMusicChanged`).
 *
 * # reasonKind discriminator (snake_case, mirrors `IslandUnloaded.reasonKind`)
 *
 *  - `"hp_threshold"` — boss / nexus HP dropped past a configured threshold
 *    (Wave 5pve-boss / Wave 5annihilation linkage).
 *  - `"phase_transition"` — match phase advanced (Wave 5annihilation /
 *    Wave 5pve-boss).
 *  - `"player_count"` — alive-player count crossed a threshold (last-man-
 *    standing → high-intensity).
 *  - `"manual"` — admin command / cutscene scripted call.
 */
public data class AdaptiveMusicTriggered(
    public val matchId: MatchId,
    public val newMood: MusicMood,
    public val selectedTrack: MusicTrackId,
    public val reasonKind: String,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))

    init {
        require(reasonKind.isNotBlank()) {
            "AdaptiveMusicTriggered.reasonKind must not be blank"
        }
        require(reasonKind in ALLOWED_REASONS) {
            "AdaptiveMusicTriggered.reasonKind must be one of $ALLOWED_REASONS (got '$reasonKind')"
        }
    }

    public companion object {
        public val ALLOWED_REASONS: Set<String> = setOf(
            "hp_threshold",
            "phase_transition",
            "player_count",
            "manual",
        )
    }
}
