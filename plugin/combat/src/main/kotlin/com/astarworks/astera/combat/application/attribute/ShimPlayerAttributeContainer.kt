package com.astarworks.astera.combat.application.attribute

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.combat.domain.resource.ManaPool

/**
 * Wave 5b transitional [IAttributeContainer] for a player. Wraps the
 * existing `PlayerStats` + `ManaPool` records so HUD widgets can read
 * HP / mana via the unified key namespace **today**, without waiting for
 * the Wave 5e migration that swings real persistence under the container.
 *
 * Per ADR-0058 §5–§6, this shim:
 *
 * - Exposes [Attributes.MANA_CURRENT] / [Attributes.MANA_MAX] /
 *   [Attributes.MANA_REGEN_PER_SEC] read-through from [ManaPool].
 * - Exposes [Attributes.MAX_HP] / [Attributes.CURRENT_HP] using
 *   [maxHpDefault] (Bukkit default 20.0). Real HP tracking moves into
 *   `IPlayerAttributeContainer` in Wave 5e.
 * - Accepts ad-hoc [set] writes to an in-memory map so tests and Wave 5c
 *   features can populate extra keys without coupling to the wrapped
 *   records.
 *
 * Unknown reads return `null`, matching the contract.
 *
 * **Why combat is the home**: ManaPool lives here. Core hosts the
 * interface but cannot depend on Combat. Stats plugin would also work,
 * but Combat is the heaviest reader of mana/HP at runtime and so the
 * shim's drift cost is lowest here.
 *
 * **Lifecycle**: one instance per (player, ManaPool snapshot). Callers
 * rebuild after `ManaPool.spend(...)` returns a new immutable copy;
 * Wave 5e replaces the snapshot reads with live state.
 */
public class ShimPlayerAttributeContainer(
    private val stats: PlayerStats,
    private val mana: ManaPool,
    private val maxHpDefault: Double = 20.0,
) : IAttributeContainer {

    private val overrides: MutableMap<AttributeKey, AttributeValue> = mutableMapOf()

    override fun get(key: AttributeKey): AttributeValue? {
        overrides[key]?.let { return it }
        return when (key) {
            Attributes.MAX_HP -> AttributeValue.NumberValue(maxHpDefault)
            Attributes.CURRENT_HP -> AttributeValue.NumberValue(maxHpDefault)
            Attributes.MANA_CURRENT -> AttributeValue.NumberValue(mana.current.toDouble())
            Attributes.MANA_MAX -> AttributeValue.NumberValue(mana.max.toDouble())
            Attributes.MANA_REGEN_PER_SEC -> AttributeValue.NumberValue(mana.regenPerSec)
            else -> null
        }
    }

    override fun set(key: AttributeKey, value: AttributeValue) {
        overrides[key] = value
    }

    override fun remove(key: AttributeKey) {
        overrides.remove(key)
    }

    override fun keys(): Set<AttributeKey> = buildSet {
        addAll(overrides.keys)
        add(Attributes.MAX_HP)
        add(Attributes.CURRENT_HP)
        add(Attributes.MANA_CURRENT)
        add(Attributes.MANA_MAX)
        add(Attributes.MANA_REGEN_PER_SEC)
    }

    /** The id of the player this shim represents. Convenience accessor for callers that hold only the container. */
    public val playerId: com.astarworks.astera.core.domain.player.PlayerId
        get() = stats.playerId
}
