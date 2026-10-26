package com.astarworks.astera.core.application.profile

import com.astarworks.astera.core.application.port.outbound.IProfileCardService
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.profile.CosmeticSummary
import com.astarworks.astera.core.domain.profile.EquipmentSummary
import com.astarworks.astera.core.domain.profile.ProfileVisibility
import com.astarworks.astera.core.domain.profile.PublicProfileCard
import com.astarworks.astera.core.domain.profile.TitleSummary
import java.time.Clock

/**
 * Recompute and persist a [PublicProfileCard] for a single player
 * (Wave 5flex / ADR-0102 surface #3).
 *
 * Each input summary comes from a different plugin via a small
 * provider lambda — the use case is intentionally vendor-neutral and
 * stays in Core. Phase C wires:
 *
 *   - [equipmentProvider] ← Items plugin
 *   - [titlesProvider] ← Stats plugin
 *   - [cosmeticsProvider] ← Phase 4 cosmetic plugin (placeholder for now)
 *   - [powerIndexTotalProvider] ← Economy plugin
 *   - [visibilityProvider] ← Wave 5e PlayerSettings (publicProfileEnabled)
 *   - [displayNameProvider] ← Core PlayerProfileService
 *
 * # Why providers, not direct ports
 *
 * Adding outbound ports for each contributing plugin would force Core
 * to know about Items / Stats / Cosmetic types — violating ADR-0022
 * isolation. Lambdas keep Core dependency-free at compile time and
 * defer the wiring to Phase C (orchestrator-only).
 */
public class RecomputeProfileCardUseCase(
    private val cards: IProfileCardService,
    private val displayNameProvider: (PlayerId) -> String?,
    private val visibilityProvider: (PlayerId) -> ProfileVisibility = { ProfileVisibility.PUBLIC },
    private val equipmentProvider: (PlayerId) -> EquipmentSummary = { EquipmentSummary.EMPTY },
    private val titlesProvider: suspend (PlayerId) -> TitleSummary = { TitleSummary.EMPTY },
    private val cosmeticsProvider: suspend (PlayerId) -> CosmeticSummary = { CosmeticSummary.EMPTY },
    private val powerIndexTotalProvider: (PlayerId) -> Long = { 0L },
    private val clock: Clock = Clock.systemUTC(),
) {

    public suspend fun recompute(playerId: PlayerId): PublicProfileCard? {
        val name = displayNameProvider(playerId) ?: return null
        val card = PublicProfileCard(
            playerId = playerId,
            displayName = name,
            visibility = visibilityProvider(playerId),
            equipment = equipmentProvider(playerId),
            titles = titlesProvider(playerId),
            cosmetics = cosmeticsProvider(playerId),
            powerIndexTotal = powerIndexTotalProvider(playerId).coerceAtLeast(0L),
            computedAt = clock.instant(),
        )
        cards.save(card)
        return card
    }
}
