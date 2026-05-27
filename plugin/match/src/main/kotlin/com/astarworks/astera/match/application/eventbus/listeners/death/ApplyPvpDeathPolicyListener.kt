package com.astarworks.astera.match.application.eventbus.listeners.death

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.inventory.ClearScope
import com.astarworks.astera.match.application.port.outbound.IPvpDeathPolicyResolver
import com.astarworks.astera.match.domain.death.PvpDeathRule
import org.slf4j.LoggerFactory

/**
 * On every [PlayerKilled] event, resolve the per-match [PvpDeathRule]
 * and translate the decision into [IInventoryMutator] calls (Wave
 * 5game-complete, ADR-0121 §E).
 *
 * # Match-aware fast path
 *
 * If the victim is not currently in any active match (PvE death,
 * lobby kill, spectator anomaly) the listener no-ops — the PvE
 * encounter zone handler ([com.astarworks.astera.core.domain.mob.encounter.DeathPenaltyRule])
 * owns non-match deaths. This listener only handles PvP-in-match.
 *
 * # Rule → action mapping
 *
 *  - [PvpDeathRule.KeepInventory] — no mutator call. Match-end rewards
 *    still fire from other listeners.
 *  - [PvpDeathRule.DropAll] — `clear(All)`. The mutator's Paper
 *    adapter is responsible for spawning the dropped items at the
 *    victim's last location (Wave 6 concern, not domain).
 *  - [PvpDeathRule.DropEquipped] — `clear(Armor)` + `clear(OffHand)`.
 *    Main-hand drop is **deferred to Wave 6** because the domain
 *    cannot resolve "currently held slot" without a Paper-side query
 *    port we don't yet have.
 *  - [PvpDeathRule.DropOnly] — Phase 1 PoC ships the resolver but the
 *    listener-side per-item probability evaluation is **deferred to
 *    Wave 6 adapter**; this listener logs a warning and treats it as
 *    KeepInventory so behaviour stays safe.
 *
 * # Why not publish a follow-up event
 *
 * The Paper adapter watches `IInventoryMutator.clear` itself when it
 * needs to spawn the visible item entities — there's no win from a
 * second cross-plugin event. Stats / audit listeners already record
 * the kill via [PlayerKilled].
 */
public class ApplyPvpDeathPolicyListener(
    private val matches: IMatchRepository,
    private val resolver: IPvpDeathPolicyResolver,
    private val inventoryMutator: IInventoryMutator,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerKilled) {
        val match = matches.findMatchOf(event.victim) ?: return
        val rule = resolver.resolveForMatch(match.id)
        when (rule) {
            PvpDeathRule.KeepInventory -> Unit

            PvpDeathRule.DropAll ->
                logIfFailure(
                    "DropAll(victim=${event.victim})",
                    inventoryMutator.clear(event.victim, ClearScope.All),
                )

            PvpDeathRule.DropEquipped -> {
                logIfFailure(
                    "DropEquipped/Armor(victim=${event.victim})",
                    inventoryMutator.clear(event.victim, ClearScope.Armor),
                )
                logIfFailure(
                    "DropEquipped/OffHand(victim=${event.victim})",
                    inventoryMutator.clear(event.victim, ClearScope.OffHand),
                )
            }

            is PvpDeathRule.DropOnly -> {
                // Probability-gated per-item drops require the Wave 6 Paper
                // adapter to read the live inventory + spin a deterministic
                // RNG. Domain stays safe: log and skip mutation.
                log.debug(
                    "DropOnly(percent={}, includes={}) deferred to Wave 6 adapter; no mutation applied for victim {}.",
                    rule.percent, rule.includes, event.victim,
                )
            }
        }
    }

    private fun logIfFailure(context: String, outcome: Result<Unit, *>) {
        when (outcome) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "PvP death-policy mutator call failed ({}): {}",
                context, outcome.error,
            )
        }
    }
}
