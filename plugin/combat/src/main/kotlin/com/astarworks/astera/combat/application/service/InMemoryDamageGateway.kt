package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Phase 1 PoC stub [IDamageGateway] (ADR-0133 §B).
 *
 * Returns [DamageApplicationError.TargetOffline] for every call. Phase 1
 * has no live Paper damage adapter (Wave 6a will land `PaperDamageGateway`
 * over Bukkit `Damageable.damage`), so from the perspective of this default
 * impl every target is effectively offline — there is no game-world hit
 * box to write hit points to. Registered at priority 0 in
 * `AsteraCombatPlugin.onEnable`; Wave 6a's Paper adapter overlays at
 * priority 100.
 *
 * Why a stub instead of leaving the port unregistered:
 * - Pre-stub state: `AsteraCombatPlugin.onEnable` null-guards the
 *   `ApplyDamageUseCase` construction (`if (damageGateway != null && …)`).
 *   With no impl, the entire damage pipeline silently does not wire,
 *   and the combo / focus-fire / target-lock damage-bonus chain
 *   (Wave K 5integration-wire / ADR-0128) cannot be exercised end-to-end
 *   in Phase 1 dev sessions.
 * - With this stub: the use case wires, the bonus chain runs through
 *   `ApplyDamageUseCase`, the gateway returns `TargetOffline`, and the
 *   caller logs the no-op. This is observable in dev logs and makes the
 *   silent-drop pattern impossible.
 *
 * Logged at DEBUG to avoid flooding INFO when damage attempts happen
 * frequently (e.g. PvE auto-attack loop). Operators inspecting why
 * damage is no-op'ing see "InMemoryDamageGateway: ..." and immediately
 * know the Paper adapter has not yet shipped (Wave 6a).
 *
 * Audit γ Round 16 finding: this port was previously the only Wave-6-
 * deferred port with both (a) no in-memory default and (b) callers that
 * null-guard on the use-case construction site, leaving the damage chain
 * silently inert. Closing the gap is a Round 16 pre-Paper-sweep task.
 */
public class InMemoryDamageGateway : IDamageGateway {

    private val log = LoggerFactory.getLogger(InMemoryDamageGateway::class.java)

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError> {
        log.debug(
            "InMemoryDamageGateway (Phase 1 PoC stub): no-op for target={} damage={} source={}",
            target,
            resolved,
            source,
        )
        return Result.failure(DamageApplicationError.TargetOffline(target))
    }
}
