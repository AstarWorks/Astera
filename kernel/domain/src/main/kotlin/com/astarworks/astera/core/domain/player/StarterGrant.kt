package com.astarworks.astera.core.domain.player

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId

/**
 * What a brand-new player receives on first login (Wave 5e-fix).
 *
 * This is the **domain-side declaration** of the first-time grant — pure
 * Kotlin, no YAML annotations, no I/O. It captures the three things a
 * first-time profile creation must seed:
 *
 *   - [starterItems]: items every new player starts with. Resolved through
 *     `IItemRegistry.find(id)` at apply-time; unknown ids degrade gracefully
 *     (logged + skipped) so renaming a starter item never wedges joins.
 *   - [walletGrants]: per-currency initial balances. Applied via
 *     `ICurrencyLedger.credit`. Empty map = no wallet credit.
 *   - [tutorialAutoEnroll]: whether to mark the tutorial script registered
 *     under [tutorialScriptId] for the new player. Wave 5e-fix exposes the
 *     flag + id; the per-player tutorial-state repository that consumes them
 *     lands in a later wave. Today this is consulted but, absent a
 *     state-repo, is a no-op record on the audit log only.
 *
 * # Pre-release posture
 *
 * Per [CLAUDE.md §Pre-release posture] this record may be re-shaped freely
 * until Phase 1 PoC closes. Authors should NOT add migration shims for old
 * YAML shapes; rewrite the on-disk file and the [toDomain] converter
 * together.
 *
 * @property starterItems ordered list of items granted. Order matters for
 *   hotbar placement when an inventory adapter (Wave 6b) consumes the grant;
 *   slot 0 wins the player's hand. List may be empty.
 * @property walletGrants per-currency initial balances. Values MUST be
 *   non-negative; a zero entry is dropped at apply-time so the audit log
 *   stays useful.
 * @property tutorialAutoEnroll if `true`, the new profile is recorded as
 *   enrolled in [tutorialScriptId] (subject to the comment above).
 * @property tutorialScriptId opaque id matching a `content/tutorials/`
 *   entry. `null` when [tutorialAutoEnroll] is `false`.
 * @property welcomeDialogId opaque id of the dialog to show on first join.
 *   `null` disables. Today no `IDialogGateway` exists in core; this field
 *   is captured for forward use and surfaced on the audit log only.
 */
public data class StarterGrant(
    val starterItems: List<ItemId>,
    val walletGrants: Map<Currency, Long>,
    val tutorialAutoEnroll: Boolean,
    val tutorialScriptId: String?,
    val welcomeDialogId: String?,
    /**
     * Field tools (pickaxe / axe / shovel) lent to every match participant for the match only, by Norma level (ADR-0319);
     * not part of the first-login grant. Empty = no tools lent. Otherwise strictly ascending by level, starting at 1,
     * every tier the same size and in the same tool-class order (so tier N+1 replaces tier N slot for slot).
     */
    val matchToolTiers: List<MatchToolTier> = emptyList(),
) {
    /** The tools lent at Norma [level]: the highest tier whose level is `<= level`; empty when no tiers are declared. */
    public fun matchToolsAt(level: Int): List<ItemId> = matchToolTiers.lastOrNull { it.level <= level }?.tools.orEmpty()

    init {
        if (matchToolTiers.isNotEmpty()) {
            require(matchToolTiers.first().level == 1) { "StarterGrant.matchToolTiers must start at level 1" }
            require(matchToolTiers.zipWithNext().all { (a, b) -> a.level < b.level }) { "StarterGrant.matchToolTiers must be strictly ascending by level" }
            require(matchToolTiers.all { it.tools.size == matchToolTiers.first().tools.size }) { "StarterGrant.matchToolTiers tiers must all list the same number of tools" }
        }
        walletGrants.forEach { (currency, amount) ->
            require(amount >= 0) {
                "StarterGrant.walletGrants[$currency] must be >= 0, got $amount"
            }
        }
        if (tutorialAutoEnroll) {
            require(!tutorialScriptId.isNullOrBlank()) {
                "StarterGrant.tutorialScriptId must be set when tutorialAutoEnroll = true"
            }
        }
    }

    public companion object {
        /** Empty grant — no loadout, no wallet credit, no tutorial. Useful in tests. */
        public val EMPTY: StarterGrant = StarterGrant(
            starterItems = emptyList(),
            walletGrants = emptyMap(),
            tutorialAutoEnroll = false,
            tutorialScriptId = null,
            welcomeDialogId = null,
        )
    }
}

/** The tools lent from Norma [level] on (until the next tier). Same order across tiers = same tool class. */
public data class MatchToolTier(val level: Int, val tools: List<ItemId>) {
    init {
        require(level >= 1) { "MatchToolTier.level must be >= 1, got $level" }
        require(tools.isNotEmpty()) { "MatchToolTier(level=$level).tools must not be empty" }
    }
}
