package com.astarworks.astera.core.domain.profile

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Out-of-game public profile card snapshot for a player
 * (Wave 5flex / ADR-0102 surface #3).
 *
 * Cross-plugin SSoT for the "what does this player look like to the
 * outside world" surface:
 *
 *   - HSB `sky.shiiyu.moe` style profile page
 *   - OSRS Hiscores card
 *   - 原神 名刺 (Calling Card)
 *
 * # Why this lives in Core
 *
 * Wave 5flex Phase C wires:
 *   - `:plugin:stats` populates the achievement + title summary
 *   - `:plugin:items` populates the equipment summary
 *   - `:plugin:economy` populates the cosmetic / power-index summary
 *   - Wave 8b REST adapter publishes the card at
 *     `/api/players/{id}/profile`
 *
 * Each plugin reads / writes the card through the [IProfileCardService]
 * port also defined in Core. Per ADR-0022 each feature plugin contributes
 * only its own slice — the card itself is a pure-data aggregate.
 *
 * # Why a snapshot, not a live read
 *
 * Computing each component on every REST request would fan out to every
 * feature plugin's database. The card is updated whenever a relevant
 * domain event fires (AchievementUnlocked, ItemInstanceRefined,
 * PowerIndexComputed); reads stay constant-time.
 *
 * # Visibility setting (ADR-0102 §Mitigations — privacy)
 *
 * The [visibility] field is owned by the player via Wave 5e
 * `PlayerSettings.publicProfileEnabled`. When [visibility] is
 * [ProfileVisibility.PRIVATE], REST returns 404 even if the snapshot
 * is present — defense in depth on top of the settings-side gate.
 */
public data class PublicProfileCard(
    public val playerId: PlayerId,
    public val displayName: String,
    public val visibility: ProfileVisibility,
    public val equipment: EquipmentSummary,
    public val titles: TitleSummary,
    public val cosmetics: CosmeticSummary,
    public val powerIndexTotal: Long,
    public val computedAt: Instant,
) {
    init {
        require(displayName.isNotBlank()) {
            "PublicProfileCard.displayName must not be blank"
        }
        require(powerIndexTotal >= 0L) {
            "PublicProfileCard.powerIndexTotal must be >= 0, got $powerIndexTotal"
        }
    }

    public companion object {
        public fun empty(
            playerId: PlayerId,
            displayName: String,
            at: Instant,
            visibility: ProfileVisibility = ProfileVisibility.PUBLIC,
        ): PublicProfileCard = PublicProfileCard(
            playerId = playerId,
            displayName = displayName,
            visibility = visibility,
            equipment = EquipmentSummary.EMPTY,
            titles = TitleSummary.EMPTY,
            cosmetics = CosmeticSummary.EMPTY,
            powerIndexTotal = 0L,
            computedAt = at,
        )
    }
}

/** Visibility setting for [PublicProfileCard]. Mirrors Wave 5e PlayerSettings. */
public enum class ProfileVisibility {
    /** Card is served by REST and embedded in Web admin. */
    PUBLIC,

    /** Card is hidden from REST + Web admin (returns 404). */
    PRIVATE,
}

/**
 * Slim equipment summary — count of refined items + the highest-tier
 * piece by ItemId. Phase 1 PoC; richer per-slot breakdown is Wave
 * 8a Web admin's concern.
 */
public data class EquipmentSummary(
    public val itemCount: Int,
    public val featuredItemId: String?,
) {
    init {
        require(itemCount >= 0) {
            "EquipmentSummary.itemCount must be >= 0, got $itemCount"
        }
    }

    public companion object {
        public val EMPTY: EquipmentSummary = EquipmentSummary(0, null)
    }
}

/**
 * Title summary — total unlocked count + the currently-equipped title
 * (if any).
 */
public data class TitleSummary(
    public val unlockedCount: Int,
    public val equippedTitleId: String?,
) {
    init {
        require(unlockedCount >= 0) {
            "TitleSummary.unlockedCount must be >= 0, got $unlockedCount"
        }
    }

    public companion object {
        public val EMPTY: TitleSummary = TitleSummary(0, null)
    }
}

/**
 * Cosmetic summary — count of unlocked cosmetic items. Phase 4 cosmetic
 * plugin (Wave 8e) populates this; Wave 5flex ships the empty
 * placeholder so the card shape is stable.
 */
public data class CosmeticSummary(
    public val unlockedCount: Int,
    public val featuredCosmeticId: String?,
) {
    init {
        require(unlockedCount >= 0) {
            "CosmeticSummary.unlockedCount must be >= 0, got $unlockedCount"
        }
    }

    public companion object {
        public val EMPTY: CosmeticSummary = CosmeticSummary(0, null)
    }
}
