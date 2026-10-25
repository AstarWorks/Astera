package com.astarworks.astera.core.domain.feedback

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.UUID

/**
 * Wave 5t (ADR-0086) — in-game player feedback (report / suggestion).
 *
 * Composed via [PlayerFeedback.submit] (Phase 1 PoC: anybody can submit;
 * Phase 2 may add per-category cooldowns). Triage by GMs through the
 * `/astera feedback list` + `/astera feedback triage` admin commands.
 *
 * Distinct from Wave 5h moderation (ban/mute/kick/warn): 5h is GM →
 * player one-way; 5t is player → GM one-way. The two trails join at the
 * audit log (Wave 5e) so admin tooling can present a unified history per
 * player.
 */
public data class PlayerFeedback(
    val id: FeedbackId,
    val submittedBy: PlayerId,
    val target: PlayerId?,                // null for `Suggestion` / `Other`
    val category: FeedbackCategory,
    val comment: String,
    val submittedAt: Instant,
    val status: FeedbackStatus,
)

@JvmInline
public value class FeedbackId(public val value: UUID) {
    public companion object {
        public fun random(): FeedbackId = FeedbackId(UUID.randomUUID())
    }
}

public sealed interface FeedbackCategory {
    public val key: String

    public data object Cheating : FeedbackCategory { override val key: String = "cheating" }
    public data object Bug : FeedbackCategory { override val key: String = "bug" }
    public data object Harassment : FeedbackCategory { override val key: String = "harassment" }
    public data object Suggestion : FeedbackCategory { override val key: String = "suggestion" }
    public data class Other(val freeForm: String) : FeedbackCategory { override val key: String = "other" }
}

public enum class FeedbackStatus {
    Open,
    InProgress,
    Resolved,
    Dismissed,
}

public sealed interface FeedbackTriageDecision {
    public data object MarkInProgress : FeedbackTriageDecision
    public data class Resolve(val noteKey: String? = null) : FeedbackTriageDecision
    public data class Dismiss(val noteKey: String? = null) : FeedbackTriageDecision
}
