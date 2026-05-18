package com.astarworks.astera.core.domain.attribute

/**
 * Concrete [IAttributeContainer] backed by an in-memory `MutableMap` of stored
 * values plus an ordered `MutableList` of modifiers (ADR-0058 §1).
 *
 * Wave 5c real-impl behind the read-API Wave 5b shipped. Used by:
 *
 * - Star Generator instances (one container per generator): carries
 *   `generator.energy` as a base [AttributeValue.NumberValue]; Sudden Death
 *   overlays a `MULTIPLY 0.5` modifier on `generator.period_multiplier`.
 * - Status-effect sites (Wave 5d) that need to layer per-tick numeric deltas.
 * - Wave 5e `PlayerStats` / `ManaPool` / `Wallet` facades; the persistence
 *   layer (V14 JSONB) reads/writes via this impl.
 *
 * ## Modifier resolution
 *
 * [effectiveNumber] honors the order documented in [AttributeModifier]:
 *
 *   1. base   = `(get(key) as? NumberValue)?.value ?: 0.0`
 *   2. **SET** modifiers (filtered by `key`) — last by registration order wins;
 *      replaces base entirely
 *   3. **ADD** modifiers — summed onto the above
 *   4. **MULTIPLY** modifiers — compounded in registration order
 *
 * ## Threading
 *
 * Not thread-safe — Astera invariant: all gameplay state mutation runs on the
 * server main thread (per `IScheduler` regional contract). Don't call from
 * `runAsync` blocks.
 *
 * ## Why not use the `IAttributeContainer` defaults?
 *
 * The default modifier methods on [IAttributeContainer] are no-ops so
 * read-only shims (e.g. `ShimPlayerAttributeContainer` in combat) compile.
 * This class overrides them with the real implementation.
 */
public class SimpleAttributeContainer : IAttributeContainer {

    private val values: MutableMap<AttributeKey, AttributeValue> = mutableMapOf()
    private val modifierList: MutableList<AttributeModifier> = mutableListOf()

    // ---- Read-API (Wave 5b surface) ------------------------------------

    override fun get(key: AttributeKey): AttributeValue? = values[key]

    override fun set(key: AttributeKey, value: AttributeValue) {
        values[key] = value
    }

    override fun remove(key: AttributeKey) {
        values.remove(key)
    }

    override fun keys(): Set<AttributeKey> = values.keys.toSet()

    // ---- Modifier surface (Wave 5c) ------------------------------------

    override fun modifiers(): List<AttributeModifier> = modifierList.toList()

    override fun addModifier(modifier: AttributeModifier) {
        modifierList += modifier
    }

    override fun removeModifiersBySource(sourceId: String) {
        modifierList.removeAll { it.sourceId == sourceId }
    }

    override fun effectiveNumber(key: AttributeKey): Double {
        val base: Double = (values[key] as? AttributeValue.NumberValue)?.value ?: 0.0

        // Apply modifiers in phase order (per AttributeModifier kdoc):
        //   1. base (already computed)
        //   2. SET — last SET by registration order replaces the base
        //   3. ADD — sum all ADDs regardless of position relative to SETs
        //   4. MULTIPLY — compound in registration order on the SET+ADD result
        val matching: List<AttributeModifier> = modifierList.filter { it.key == key }

        val afterSet: Double = matching
            .lastOrNull { it.operation == AttributeModifier.Operation.SET }
            ?.value
            ?: base

        val addSum: Double = matching
            .filter { it.operation == AttributeModifier.Operation.ADD }
            .sumOf { it.value }

        var result: Double = afterSet + addSum
        for (mod in matching) {
            if (mod.operation == AttributeModifier.Operation.MULTIPLY) {
                result *= mod.value
            }
        }
        return result
    }
}
