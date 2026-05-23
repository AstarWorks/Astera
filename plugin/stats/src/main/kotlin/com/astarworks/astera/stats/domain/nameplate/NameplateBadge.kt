package com.astarworks.astera.stats.domain.nameplate

import com.astarworks.astera.core.domain.achievement.TitleId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Match-nameplate "badge" rendered alongside a player's display name
 * (Wave 5flex / ADR-0102 surface #2).
 *
 * Combines four signals so the nameplate row says, at a glance:
 *
 *   - **Title** (Wave 5e Achievement title) — short i18n label
 *   - **Certification tier** (Wave 11d Certification, placeholder for
 *     now) — numeric tier 0..max
 *   - **Power Index** (Wave 5flex surface #4) — Networth aggregate
 *   - **Milestone** (Wave 5flex surface #5) — single highlighted
 *     achievement (`kill 1000`, `boss defeat 100`, etc.)
 *
 * Phase 1 PoC scope: Wave 5flex computes and renders the badge via
 * Wave 5b `ITabList`. Wave 11d will populate the certification tier
 * for real; until then the placeholder defaults to tier 0.
 *
 * # Why a value object
 *
 * The badge is a pure projection — every field derives from existing
 * state in feature plugins. Reads stay cheap because the badge is
 * cached per-player in the [com.astarworks.astera.stats.application.service.nameplate.NameplateBadgeRegistry]
 * (in-memory only for Wave 5flex; no persistence — the badge is rebuilt
 * on join + every PowerIndexComputed / AchievementUnlocked event).
 *
 * # MiniMessage rendering
 *
 * The Paper-side renderer composes the placeholders with the
 * `astera.stats.nameplate.badge` i18n template:
 *
 *   `[{title}] {milestone} {name} ⚜{tier} ⛁{power}`
 *
 * Variants ([NoTitle], [NoMilestone]) are still legal — the template
 * tolerates blank placeholders and renders gracefully.
 */
public data class NameplateBadge(
    public val playerId: PlayerId,
    public val titleId: TitleId?,
    public val titleDisplayKey: MessageKey?,
    public val certificationTier: Int,
    public val powerIndexTotal: Long,
    public val milestoneKey: MessageKey?,
) {
    init {
        require(certificationTier >= 0) {
            "NameplateBadge.certificationTier must be >= 0, got $certificationTier"
        }
        require(powerIndexTotal >= 0L) {
            "NameplateBadge.powerIndexTotal must be >= 0, got $powerIndexTotal"
        }
        if (titleId != null) {
            requireNotNull(titleDisplayKey) {
                "NameplateBadge.titleDisplayKey must be present when titleId is set"
            }
        }
    }

    /** Convenience: nothing to render. Common for fresh players. */
    public val isEmpty: Boolean
        get() = titleId == null &&
            certificationTier == 0 &&
            powerIndexTotal == 0L &&
            milestoneKey == null

    public companion object {
        /** Badge with no signals — used when the player has no progression yet. */
        public fun empty(playerId: PlayerId): NameplateBadge = NameplateBadge(
            playerId = playerId,
            titleId = null,
            titleDisplayKey = null,
            certificationTier = 0,
            powerIndexTotal = 0L,
            milestoneKey = null,
        )
    }
}
