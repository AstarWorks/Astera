package com.astarworks.astera.core.domain.attribute

/**
 * Universal attribute container (ADR-0058 §1).
 *
 * One interface that players, mobs (Wave 5d), item-instances (Wave 6b), and
 * future projectile state (Wave 6+) all implement. Each subject type keeps
 * its own carrier (`IPlayerAttributeContainer` / `IMobAttributeContainer` /
 * `IItemAttributeContainer`), but the read shape is identical.
 *
 * ## Wave 5b read-API + Wave 5c modifier surface
 *
 * Wave 5b shipped the read API (`get` / `set` / `remove` / `keys`). Wave 5c
 * extends the interface with the **modifier surface**
 * ([addModifier] / [removeModifiersBySource] / [modifiers] / [effectiveNumber])
 * so Sudden-Death accelerators, equipped-item bonuses, and status effects
 * can stack via a single uniform primitive.
 *
 * Default implementations of the modifier methods are **no-op** so read-only
 * adapters (e.g. early Wave 5b HUD prototype) keep compiling. Real
 * implementations override them — see [SimpleAttributeContainer].
 *
 * ## Use sites
 *
 * - **Wave 5c**: `SimpleAttributeContainer` per Star Generator instance —
 *   carries `generator.energy` + accepts Sudden Death `MULTIPLY 0.5`
 *   modifier on `generator.period_multiplier`.
 * - **Wave 5e (current)**: `Wallet`, `PlayerStats`, `ManaPool` are facades
 *   over `IAttributeContainer`. Persistence (V14 JSONB) backs every player
 *   row through `IPlayerAttributeContainerStore`.
 * - **Wave 5d / 6b**: mob and item-instance containers.
 *
 * Implementations are NOT required to be thread-safe; callers serialize
 * access on the server main thread.
 */
public interface IAttributeContainer {
    /** Return the raw value stored for [key], or `null` if absent. */
    public fun get(key: AttributeKey): AttributeValue?

    /** Store [value] under [key], replacing any previous value. */
    public fun set(key: AttributeKey, value: AttributeValue)

    /** Remove any value stored under [key]. No-op if absent. */
    public fun remove(key: AttributeKey)

    /** Currently-populated keys. Order is implementation-defined. */
    public fun keys(): Set<AttributeKey>

    // ---- Modifier surface (ADR-0058 §1; Wave 5c) -----------------------

    /**
     * All modifiers currently overlaid on this container, in registration
     * order. Default: empty (read-only shims have no modifier state).
     */
    public fun modifiers(): List<AttributeModifier> = emptyList()

    /**
     * Add [modifier] to this container. Default: no-op. Real impls store
     * the modifier and incorporate it in subsequent [effectiveNumber] reads.
     */
    public fun addModifier(modifier: AttributeModifier) {
        // no-op for read-only shims
    }

    /**
     * Remove every modifier whose `sourceId` equals [sourceId]. Default:
     * no-op. Used by unequip / status-expiry / phase-exit cleanup.
     */
    public fun removeModifiersBySource(sourceId: String) {
        // no-op for read-only shims
    }

    /**
     * Resolve effective numeric value of [key] applying modifiers per the
     * order documented in [AttributeModifier]:
     *
     *   1. base = `(get(key) as? NumberValue)?.value ?: 0.0`
     *   2. **SET** modifiers replace base (last by registration order wins)
     *   3. **ADD** modifiers sum onto the result
     *   4. **MULTIPLY** modifiers compound in registration order
     *
     * Default implementation reads the base value only and ignores modifiers
     * — sufficient for read-only shims. Impls that store modifiers (e.g.
     * `SimpleAttributeContainer`) override.
     */
    public fun effectiveNumber(key: AttributeKey): Double =
        (get(key) as? AttributeValue.NumberValue)?.value ?: 0.0
}
