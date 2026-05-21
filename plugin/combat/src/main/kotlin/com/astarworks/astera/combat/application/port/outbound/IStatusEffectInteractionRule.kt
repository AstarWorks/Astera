package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.interaction.StatusInteraction

/**
 * Resolver for the Wave 5o status-effect interaction matrix (ADR-0081).
 *
 * Given an incoming [StatusEffectInstance] about to be applied to a player and
 * the collection of effects already on that player, returns a
 * [StatusInteraction] telling the caller what to do — cancel / suppress /
 * amplify / pass.
 *
 * # Wiring expectations
 *
 *  - The Wave 5o `YamlStatusEffectInteractionRule` reads
 *    `content/balance/status-effect-interactions.yaml` and resolves rules in
 *    declared order; the first matching rule wins.
 *  - Listeners that observe [com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer.apply]
 *    consult this rule before calling `apply` and act on the returned
 *    interaction (publishing `StatusEffectCancelled` / scheduling immunity
 *    windows / replaying with scaled magnitude).
 *  - In test fixtures inject a stub returning `StatusInteraction.Pass` so the
 *    apply path stays linear.
 *
 * # Konsist
 *
 * Implementations live in `application/service/` (Bukkit-free) and are
 * exercised via fakes from `:plugin:test-fixtures`.
 */
public interface IStatusEffectInteractionRule {

    /**
     * Resolve the interaction between [incoming] and [existing].
     *
     * The [existing] collection is the snapshot of the target's active
     * effects at the time of resolution; implementations MUST NOT mutate it.
     */
    public fun resolve(
        incoming: StatusEffectInstance,
        existing: Collection<StatusEffectInstance>,
    ): StatusInteraction
}
