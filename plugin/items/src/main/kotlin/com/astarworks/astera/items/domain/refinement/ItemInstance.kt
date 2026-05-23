package com.astarworks.astera.items.domain.refinement

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Per-player concrete materialisation of a refinable item (Wave 5refine,
 * ADR-0100).
 *
 * Where [com.astarworks.astera.core.domain.item.ItemSpec] is the *template*
 * (loaded from `content/items/<id>.yaml`, identical for every player), an
 * [ItemInstance] is one **player-owned copy** that carries:
 *
 *   - per-instance refinement layers ([layers]) — Reforge / Enchant /
 *     Gemstone / Star / Recomb, sealed and additive (ADR-0100 §Decision).
 *   - per-instance attribute overlay ([container]) — sub-stat rolls
 *     (原神聖遺物 style) and layer-derived [AttributeModifier]s composed via
 *     the existing universal container surface (ADR-0058 §1).
 *   - per-instance reroll counter ([rerollCount]) — drives the escalating
 *     cost ladder in `ApplyRefinementUseCase` (Warframe Riven cost-pattern
 *     reference per ADR-0100).
 *
 * # Why one container per instance
 *
 * Reusing [IAttributeContainer] for per-instance state means Combat reads
 * effective stats through the *same* primitive it uses for player resources
 * (ADR-0058 §1 §5). No new resolution code, no new modifier semantics —
 * `effectiveNumber(key)` already honors SET/ADD/MULTIPLY exactly the way
 * ADR-0100 §Decision wants (HSB 8-layer multiplicative composition).
 *
 * # Persistence shape
 *
 * The Postgres overlay (V30 `item_instances`) serialises [layers] +
 * [subStatRolls] to JSONB; [container] is rebuilt on read from those records
 * via [rebuildContainer]. The container is *derived state* — never persisted
 * directly — so adding a new modifier kind is purely additive.
 *
 * # Sub-stat reroll model (Warframe 反例)
 *
 * Rerolls produce a *candidate* set; the player keeps the better of old vs new
 * (`RerollSubStatsUseCase` invariant). Past rolls are never destroyed silently
 * — that was the 9-year community grievance against Warframe Riven per
 * `docs/competitive-analysis/05-warframe.md`. The use case enforces the keep /
 * swap branch; the domain only stores the chosen state.
 */
public class ItemInstance(
    public val id: ItemInstanceId,
    public val itemId: ItemId,
    public val owner: PlayerId,
    public val createdAt: Instant,
    layers: List<Refinement> = emptyList(),
    subStatRolls: List<SubStatRoll> = emptyList(),
    rerollCount: Int = 0,
) {
    private val _layers: MutableList<Refinement> = layers.toMutableList()
    private val _subStatRolls: MutableMap<SubStatKey, SubStatRoll> =
        subStatRolls.associateBy { it.key }.toMutableMap()
    private var _rerollCount: Int = rerollCount

    /** Backing container — rebuilt eagerly from [_layers] + [_subStatRolls]. */
    public val container: IAttributeContainer = SimpleAttributeContainer()

    init {
        require(rerollCount >= 0) {
            "ItemInstance.rerollCount must be >= 0, got $rerollCount"
        }
        rebuildContainer()
    }

    /** Snapshot of layers in registration order. */
    public fun layers(): List<Refinement> = _layers.toList()

    /** Snapshot of sub-stat rolls keyed by [SubStatKey] in stable yamlKey order. */
    public fun subStatRolls(): List<SubStatRoll> =
        SubStatKey.entries.mapNotNull { _subStatRolls[it] }

    /** Lookup for a single sub-stat (`null` if not rolled). */
    public fun subStatRoll(key: SubStatKey): SubStatRoll? = _subStatRolls[key]

    public fun rerollCount(): Int = _rerollCount

    /**
     * Add a [Refinement] layer to the instance's stack. Re-runs
     * [rebuildContainer] so the new layer's [AttributeModifier]s become
     * visible immediately. Returns the count of layers after the addition.
     */
    public fun addLayer(layer: Refinement): Int {
        _layers += layer
        rebuildContainer()
        return _layers.size
    }

    /**
     * Replace the entire sub-stat roll set with [next]. Used by
     * [com.astarworks.astera.items.application.usecase.refinement.RerollSubStatsUseCase]
     * after the player has chosen *keep* (this method) or *discard* (no call).
     * Increments [rerollCount] regardless so the cost ladder advances.
     */
    public fun replaceSubStats(next: List<SubStatRoll>) {
        _subStatRolls.clear()
        next.forEach { _subStatRolls[it.key] = it }
        _rerollCount += 1
        rebuildContainer()
    }

    /**
     * No-op-but-bump path used by
     * [com.astarworks.astera.items.application.usecase.refinement.RerollSubStatsUseCase]
     * when the player *discards* a fresh roll (keeps the old rolls but pays
     * the cost). Container state is unchanged but the reroll counter advances.
     */
    public fun bumpRerollCount() {
        _rerollCount += 1
    }

    /**
     * Apply [_layers] + [_subStatRolls] to [container] via [AttributeModifier].
     *
     * Removes any prior `refinement:` source modifiers first, then re-emits a
     * fresh set so the container is exactly the projection of the current
     * persistent state. The source-id convention is `refinement:<instanceId>`
     * for layer-derived modifiers and the sub-stat magnitude is stored as a
     * base [AttributeValue.NumberValue] on [SubStatKey.containerKey].
     *
     * Combat reads the effective stat through `container.effectiveNumber(key)`
     * — both the per-instance overlay (here) and any future player-side
     * modifiers compose via the existing ADR-0058 resolution order.
     */
    public fun rebuildContainer() {
        val sourceId = "refinement:$id"
        container.removeModifiersBySource(sourceId)

        // Sub-stat rolls: base value on the namespaced container key.
        // Other consumers can read with container.effectiveNumber(key).
        for (key in SubStatKey.entries) {
            val attrKey = AttributeKey(key.containerKey)
            val roll = _subStatRolls[key]
            if (roll != null) {
                container.set(attrKey, AttributeValue.NumberValue(roll.magnitude))
            } else {
                container.remove(attrKey)
            }
        }

        // Layer-derived modifiers. The contribution coefficient lives with
        // each variant — see RefinementMath.
        for (layer in _layers) {
            val (key, op, value) = RefinementMath.modifierFor(layer)
            container.addModifier(
                AttributeModifier(
                    key = key,
                    operation = op,
                    value = value,
                    sourceId = sourceId,
                ),
            )
        }
    }
}
