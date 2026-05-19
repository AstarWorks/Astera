package com.astarworks.astera.combat.application.usecase.equipment

import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.combat.domain.rule.ArmorReductionModifier
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Push the equipped [ArmorBundle]'s per-attribute resist data onto the
 * wearer's [IAttributeContainer] as `Attributes.RESIST_*`
 * [AttributeModifier]s (Wave 5e, ADR-0058 §armor).
 *
 * # Where this lives in the lifecycle
 *
 * Phase C wires this use case to a `LoadoutChanged` (or equivalent
 * equip-event) subscription on `IDomainEventBus`. On every change to a
 * player's armor bundle the handler calls [applyEquipped] with the new
 * bundle; the use case:
 *
 *  1. removes any previously-applied armor modifiers (prefix-match by
 *     `"armor:<slot>"` so re-equipping a different piece in the same slot
 *     replaces the old modifiers atomically without leaking the prior
 *     contribution); and
 *  2. for every `(slot, ArmorSpec.reduction.perAttribute[damageAttribute])`
 *     pair, adds an `AttributeModifier(RESIST_<damageAttribute>, ADD,
 *     reductionFraction, sourceId = "armor:<slot>:<armorSpecId>")` so the
 *     damage hot-path read in
 *     [ArmorReductionModifier.apply] just queries
 *     `container.effectiveNumber(Attributes.RESIST_<attribute>)`.
 *
 * The `ArmorReduction.default` fallback (per-piece "blanket" resist for
 * attributes not listed in `perAttribute`) IS written to the container too —
 * we materialise one modifier per `DamageAttribute` for which the spec
 * declares a non-zero value (either `default > 0` or a `perAttribute`
 * entry). This is intentional: the damage hot path doesn't know how to
 * roll a "default" through the container (it only reads single keys), so
 * the expansion happens at equip time. The keyspace stays bounded (six
 * attributes × four slots = 24 max modifiers per player from armor).
 *
 * # Cap is enforced downstream
 *
 * The 80% cap (`ArmorReductionModifier.DEFAULT_MAX_REDUCTION`) is NOT
 * enforced at write time — additive modifier values across slots could
 * sum past 1.0 here and `ADD` modifiers don't see each other. The cap
 * stays in `ArmorReductionModifier` (option a in the Wave 5e handoff):
 * the modifier reads `effectiveNumber(RESIST_X)`, adds set bonuses, then
 * does `coerceAtMost(maxReduction)` before the multiplicative damage
 * reduction step. This keeps write-time logic boring and keeps the cap
 * semantics in one obvious place.
 *
 * # Per-slot vs per-piece source IDs
 *
 * Each modifier's `sourceId` is `"armor:<slotName>:<armorSpecId>"`:
 *  - `<slotName>` lets `removeArmorModifiersForSlot` strip every modifier
 *    belonging to that slot via a prefix match, even when we don't yet
 *    know which armor piece used to occupy it.
 *  - `<armorSpecId>` is informational (telemetry, future debug dumps); it
 *    isn't used for filtering today but disambiguates between modifier
 *    instances if a debug overlay walks `container.modifiers()`.
 *
 * # Thread safety
 *
 * Same contract as [IAttributeContainer] — main-thread only.
 */
public class ApplyArmorModifiersUseCase(
    private val containerStore: IPlayerAttributeContainerStore,
    private val armorRegistry: IArmorRegistry,
) {

    /**
     * Reconcile [equipped] onto [playerId]'s container. Existing armor
     * modifiers (every slot's `"armor:<slot>:*"`) are removed first to
     * avoid double-stacking on re-equip; the new ones are then added.
     */
    public fun applyEquipped(
        playerId: PlayerId,
        equipped: ArmorBundle,
    ): Result<Unit, ArmorModifierError> {
        val container = containerStore.containerFor(playerId)

        // Validate every spec exists in the registry up front so a single
        // unknown id aborts the reconciliation cleanly instead of leaving
        // the container in a half-updated state.
        for ((slot, spec) in equipped.equipped) {
            if (armorRegistry.find(spec.id) == null) {
                return Result.failure(
                    ArmorModifierError.ArmorSpecNotFound(slot = slot, id = spec.id.value),
                )
            }
        }

        // Phase 1: strip every existing armor modifier (across all slots).
        // Cheaper + simpler than per-slot reconciliation, and matches the
        // expected event frequency — equip changes are rare relative to
        // damage events that READ the container.
        for (slot in ArmorSlot.entries) {
            removeArmorModifiersForSlot(container, slot)
        }

        // Phase 2: write fresh modifiers for the new bundle.
        for ((slot, spec) in equipped.equipped) {
            addModifiersForSpec(container, slot, spec)
        }

        return Result.success(Unit)
    }

    /**
     * Strip every modifier whose [AttributeModifier.sourceId] starts with
     * `"armor:<slot>"` from [container]. Wraps
     * [IAttributeContainer.removeModifiersBySource] (which is exact-match
     * only) with a per-source enumeration: collect distinct matching
     * sourceIds first, then remove each by exact match. Safe to call
     * with an empty container (no-op).
     */
    public fun removeArmorModifiersForSlot(
        container: IAttributeContainer,
        slot: ArmorSlot,
    ) {
        val prefix = sourcePrefixFor(slot)
        val toRemove = container.modifiers()
            .map { it.sourceId }
            .filter { it.startsWith(prefix) }
            .toSet()
        toRemove.forEach { container.removeModifiersBySource(it) }
    }

    private fun addModifiersForSpec(
        container: IAttributeContainer,
        slot: ArmorSlot,
        spec: ArmorSpec,
    ) {
        val sourceId = sourceIdFor(slot, spec)
        for (attribute in DamageAttribute.entries) {
            val value = spec.reduction.forAttribute(attribute)
            if (value <= 0.0) continue
            container.addModifier(
                AttributeModifier(
                    key = ArmorReductionModifier.resistKeyFor(attribute),
                    operation = AttributeModifier.Operation.ADD,
                    value = value,
                    sourceId = sourceId,
                ),
            )
        }
    }

    public companion object {
        /**
         * `"armor:<slotName>"` — prefix used by
         * [removeArmorModifiersForSlot] to strip every modifier sourced by
         * any armor piece previously occupying [slot].
         */
        public fun sourcePrefixFor(slot: ArmorSlot): String =
            "armor:${slot.name}"

        /**
         * Full source-id `"armor:<slotName>:<armorSpecId>"` for the
         * modifiers written when [spec] is equipped in [slot].
         */
        public fun sourceIdFor(slot: ArmorSlot, spec: ArmorSpec): String =
            "${sourcePrefixFor(slot)}:${spec.id.value}"
    }
}

/** Expected failure modes of [ApplyArmorModifiersUseCase.applyEquipped]. */
public sealed class ArmorModifierError {
    /**
     * The bundle referenced an [ArmorSpec] not present in
     * [IArmorRegistry]. Indicates a content sync bug — the registry was
     * reloaded mid-match, or the equip event raced ahead of registry load.
     */
    public data class ArmorSpecNotFound(
        val slot: ArmorSlot,
        val id: String,
    ) : ArmorModifierError()
}
