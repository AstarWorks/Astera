package com.astarworks.astera.stats.application.usecase.nameplate

import com.astarworks.astera.core.domain.achievement.TitleId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.nameplate.NameplateBadgeRegistry
import com.astarworks.astera.stats.domain.nameplate.NameplateBadge

/**
 * Recompute and cache a player's [NameplateBadge] (Wave 5flex /
 * ADR-0102 surface #2).
 *
 * # Providers
 *
 * Per ADR-0022 sibling-isolation: Stats cannot import
 * `:plugin:economy` types. The Power Index Long is supplied via
 * [powerIndexTotalProvider], wired at Phase C orchestrator using
 * `services.get<IPowerIndexRepository>()` lookup. Certification tier
 * lands the same way once Wave 11d ships.
 *
 * The titles tuple is owned by Stats (Title is a Stats type) and
 * resolved through [equippedTitleProvider].
 *
 * # When this fires
 *
 * Phase C wires three triggers:
 *   1. Player join → recompute once, cache the result.
 *   2. `AchievementUnlocked` event → recompute (title may have changed).
 *   3. `PowerIndexComputed` event → recompute (Power Index changed).
 *
 * The use case is idempotent — re-running with the same inputs yields
 * the same badge and overwrites the cached value harmlessly.
 *
 * Returns the freshly-cached badge so callers can use it without a
 * second registry lookup.
 */
public class RecomputeNameplateBadgeUseCase(
    private val cache: NameplateBadgeRegistry,
    private val equippedTitleProvider: (PlayerId) -> EquippedTitle?,
    private val certificationTierProvider: (PlayerId) -> Int = { 0 },
    private val powerIndexTotalProvider: (PlayerId) -> Long = { 0L },
    private val milestoneKeyProvider: (PlayerId) -> MessageKey? = { null },
) {

    /**
     * Tuple representing a player's currently-equipped Title. `null` =
     * no title equipped (fresh player or explicitly cleared).
     */
    public data class EquippedTitle(
        public val id: TitleId,
        public val displayKey: MessageKey,
    )

    public fun recompute(playerId: PlayerId): NameplateBadge {
        val title = equippedTitleProvider(playerId)
        val badge = NameplateBadge(
            playerId = playerId,
            titleId = title?.id,
            titleDisplayKey = title?.displayKey,
            certificationTier = certificationTierProvider(playerId).coerceAtLeast(0),
            powerIndexTotal = powerIndexTotalProvider(playerId).coerceAtLeast(0L),
            milestoneKey = milestoneKeyProvider(playerId),
        )
        cache.put(badge)
        return badge
    }
}
