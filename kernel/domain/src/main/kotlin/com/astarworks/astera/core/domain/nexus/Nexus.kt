package com.astarworks.astera.core.domain.nexus

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId

/**
 * A single pillar in a team's 3-pillar Nexus (ADR-0108, Wave 5annihilation).
 *
 * Astera Annihilation's defensive core is **3 pillars per team** rather than
 * the single Nexus block of Hypixel Annihilation — the extra two tiers
 * provide "graceful degradation" so that losing the outer pillars hurts
 * (respawn timing / resource regen debuffs) without instantly ending the
 * team's match. See [NexusPillarTier] for the per-tier debuff semantics.
 *
 * ## Domain purity
 *
 * `Nexus` is **pure data + a `applyDamage` transition function**. No I/O,
 * no Bukkit, no events. The owning aggregate
 * (`AnnihilationMatchState`) is responsible for emitting the corresponding
 * [com.astarworks.astera.core.domain.event.NexusDamaged] /
 * [com.astarworks.astera.core.domain.event.NexusDestroyed] events to the
 * bus.
 *
 * ## Immutability
 *
 * `applyDamage` returns a *new* `Nexus` rather than mutating in place. The
 * tracker holds the latest copy per [NexusId]; this keeps the value object
 * thread-safe by construction (Paper main thread serialises mutations but
 * the immutable shape is still cheaper to reason about).
 *
 * ## HP / damage discipline
 *
 * - HP is bounded `0 <= hp <= maxHp`; `applyDamage(0)` is a no-op,
 *   `applyDamage(negative)` is rejected at the require boundary.
 * - When [state] is [NexusState.BUILDING] (Phase 1 "Build" window), damage
 *   is no-op'd (the pillar is immune). This is defensive: the Paper-side
 *   listener in Wave 6 will cancel the underlying block break, but the
 *   domain must not trust the listener to be wired up.
 * - When [state] is [NexusState.DESTROYED], damage is no-op'd (HP already
 *   at zero, nothing to subtract).
 * - HP hitting zero in [NexusState.VULNERABLE] transitions to
 *   [NexusState.DESTROYED]. The caller observes the state change via
 *   [Nexus.applyDamage]'s return and the boolean returned alongside it
 *   (`(Nexus, NexusDamageOutcome)` from [Nexus.applyDamage]).
 */
public data class Nexus(
    public val id: NexusId,
    public val matchId: MatchId,
    public val owningTeam: TeamId,
    public val tier: NexusPillarTier,
    public val maxHp: Int,
    public val hp: Int,
    public val state: NexusState,
) {

    init {
        require(maxHp > 0) { "Nexus.maxHp must be > 0 (id=${id.value}, got=$maxHp)" }
        require(hp in 0..maxHp) {
            "Nexus.hp must be in 0..$maxHp (id=${id.value}, got=$hp)"
        }
        // Cross-field invariants — sanity guards against bad constructor calls.
        if (state == NexusState.DESTROYED) {
            require(hp == 0) { "Nexus.state DESTROYED requires hp==0 (id=${id.value}, got=$hp)" }
        }
        if (state == NexusState.BUILDING) {
            require(hp == maxHp) {
                "Nexus.state BUILDING requires hp==maxHp (id=${id.value}, got=$hp/$maxHp)"
            }
        }
    }

    /**
     * Subtract [amount] HP from this pillar.
     *
     * Returns the updated `Nexus` paired with a [NexusDamageOutcome] describing
     * what happened. The tracker uses the outcome to publish the right
     * domain event without re-deriving "did this hit destroy the pillar".
     *
     * Semantics:
     *  - [NexusState.BUILDING] → [NexusDamageOutcome.Ignored] (still building).
     *  - [NexusState.DESTROYED] → [NexusDamageOutcome.Ignored] (already dead).
     *  - [NexusState.VULNERABLE] + non-lethal → [NexusDamageOutcome.Damaged]
     *    with the new HP value.
     *  - [NexusState.VULNERABLE] + HP hits zero → [NexusDamageOutcome.Destroyed]
     *    with the previous HP and final HP=0.
     */
    public fun applyDamage(amount: Int): Pair<Nexus, NexusDamageOutcome> {
        require(amount >= 0) { "applyDamage amount must be >= 0 (id=${id.value}, got=$amount)" }
        if (amount == 0) return this to NexusDamageOutcome.Ignored(reason = IgnoreReason.ZERO_AMOUNT)
        return when (state) {
            NexusState.BUILDING ->
                this to NexusDamageOutcome.Ignored(reason = IgnoreReason.BUILDING)
            NexusState.DESTROYED ->
                this to NexusDamageOutcome.Ignored(reason = IgnoreReason.ALREADY_DESTROYED)
            NexusState.VULNERABLE -> {
                val nextHp = (hp - amount).coerceAtLeast(0)
                if (nextHp == 0) {
                    copy(hp = 0, state = NexusState.DESTROYED) to
                        NexusDamageOutcome.Destroyed(previousHp = hp)
                } else {
                    copy(hp = nextHp) to
                        NexusDamageOutcome.Damaged(previousHp = hp, currentHp = nextHp)
                }
            }
        }
    }

    /**
     * Promote a [NexusState.BUILDING] pillar to [NexusState.VULNERABLE].
     *
     * Called by the tracker when the global `AnnihilationPhase.Build` timer
     * expires (phase transition from `Phase1Build` → `Phase2Engage`). Idempotent:
     * calling on a pillar that is already [NexusState.VULNERABLE] or
     * [NexusState.DESTROYED] returns the same instance.
     */
    public fun openForCombat(): Nexus = when (state) {
        NexusState.BUILDING -> copy(state = NexusState.VULNERABLE)
        else -> this
    }

    /** Convenience: this pillar has been destroyed. */
    public val isDestroyed: Boolean get() = state == NexusState.DESTROYED

    /** Convenience: this pillar may currently take damage. */
    public val isVulnerable: Boolean get() = state == NexusState.VULNERABLE
}

/**
 * Outcome of a [Nexus.applyDamage] call (ADR-0108, Wave 5annihilation).
 *
 * Sealed so the tracker can exhaust-match without an `else` and so adding a
 * future variant (e.g. `Reflected` if Phase 2 introduces a damage-reflection
 * status) forces every call-site to acknowledge it.
 */
public sealed interface NexusDamageOutcome {

    /** Damage applied; pillar still alive. [previousHp] > [currentHp]. */
    public data class Damaged(val previousHp: Int, val currentHp: Int) : NexusDamageOutcome

    /** Damage applied; pillar destroyed by this hit. [previousHp] > 0. */
    public data class Destroyed(val previousHp: Int) : NexusDamageOutcome

    /** Damage rejected; see [reason]. */
    public data class Ignored(val reason: IgnoreReason) : NexusDamageOutcome
}

/**
 * Why [Nexus.applyDamage] returned [NexusDamageOutcome.Ignored] (ADR-0108).
 *
 * Pure enum; consumers may surface a localised log line via
 * `astera.gamemode.annihilation.ignored.<reason>` if they want player-facing
 * feedback. Default behaviour is silently no-op'd.
 */
public enum class IgnoreReason {

    /** `amount == 0` — call had no effect by construction. */
    ZERO_AMOUNT,

    /** Pillar is in [NexusState.BUILDING] — Phase 1 "Build" window. */
    BUILDING,

    /** Pillar is in [NexusState.DESTROYED] — HP already at zero. */
    ALREADY_DESTROYED,
}
