package com.astarworks.astera.core.domain.mob

import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Vendor-neutral specification for a mob registered with `IMobRegistry`
 * (Wave 5pve, ADR-0085).
 *
 * One YAML file under `content/mobs/<id>.yaml` deserializes into one
 * [MobSpec] via the application-layer DTO ([com.astarworks.astera.core.application.config.mob.MobYamlConfig]).
 * The spec is the SSoT for everything an adapter needs to spawn the mob:
 * identity, presentation, attributes, equipment, skill bindings, drops,
 * spawn rules, and AI hint.
 *
 * # Two-tier adapter strategy (ADR-0085)
 *
 * The spec is **adapter-agnostic** — every field uses Core's vendor-neutral
 * vocabulary. The Minimal adapter (`MinimalMobAdapter`) renders the spec
 * with ArmorStand + Display entities and routes [skillBindings] through
 * `MobSkillRouter` → `SkillCastRequested`. The MythicMobs adapter (Wave 6b
 * skeleton) translates the spec into a MythicMobs MobSpec at runtime. Both
 * adapters consume the same [MobSpec] type — only the rendering / AI side
 * differs.
 *
 * # Why attributes are an [IAttributeContainer]
 *
 * Per ADR-0058 (universal attribute container), HP / damage / speed /
 * resistance are all attribute keys. The spec carries a container instance
 * rather than a fixed `(maxHp, damage, speed)` triple so per-mob bonuses,
 * status-effect modifiers, and Phase 4 ladder scaling can layer through the
 * standard modifier API without changing the spec shape.
 *
 * # Skill bindings reuse Astera SkillId
 *
 * Per ADR-0085 §"Astera skill reuse": a mob attack and a player attack are
 * the same skill primitive. The MythicMobs adapter maps its native skill
 * vocabulary to Astera [com.astarworks.astera.core.domain.skill.SkillId] at
 * the translation boundary; the Minimal adapter publishes the SkillId
 * directly through `MobSkillRouter`. Combat (which subscribes to
 * `SkillCastRequested`) resolves the skill identically regardless of who
 * cast it.
 *
 * @property id Stable namespaced identifier — primary registry key.
 * @property displayName Inline localized name (ADR-0073 pattern from items).
 * @property baseEntityType Vanilla / well-known entity category the adapter
 *   should base the mob on. Maps to the Wave 5l entity registry vocabulary.
 * @property attributes Universal attribute container. Conventional keys:
 *   `mob.max_hp`, `mob.damage`, `mob.speed`, `mob.armor`, `mob.knockback_resistance`.
 * @property appearance Visual presentation overrides. Defaults to
 *   [MobAppearance.DEFAULT] (vanilla appearance, hidden name tag).
 * @property equipment Per-slot item placements applied after spawn. Each
 *   pair is (slot, item-template-id). Adapter resolves [ItemId] via
 *   `IItemRegistry`.
 * @property skillBindings Sealed [MobSkillBinding] list — triggers fire the
 *   referenced `SkillId` through `MobSkillRouter`.
 * @property lootTable Reference to a Phase 4 loot table. Use
 *   [LootTableId.EMPTY] for no drops. The Phase 4 `IPveLootDistributor`
 *   resolves the id at kill time.
 * @property spawnConditions Filter consulted by `IMobSpawnLadder` before
 *   committing to spawn. Defaults to [SpawnConditions.ANY].
 * @property ai Adapter-advisory AI behaviour hint. Defaults to
 *   [MobAiHint.Default].
 * @property vanillaEntity Vanilla entity key the body is built on (`minecraft:zombie`,
 *   ADR-0249). `null` = the mob has no physical body (the adapter refuses to spawn it).
 */
public data class MobSpec(
    public val id: MobTypeId,
    public val displayName: LocalizedText,
    public val baseEntityType: EntityCategory,
    public val attributes: IAttributeContainer,
    public val appearance: MobAppearance = MobAppearance.DEFAULT,
    public val equipment: List<Pair<EquipSlot, ItemId>> = emptyList(),
    public val skillBindings: List<MobSkillBinding> = emptyList(),
    public val lootTable: LootTableId = LootTableId.EMPTY,
    public val spawnConditions: SpawnConditions = SpawnConditions.ANY,
    public val ai: MobAiHint = MobAiHint.Default,
    public val vanillaEntity: String? = null,
) {
    init {
        // Equipment uniqueness: a single mob cannot bind two items to the
        // same slot. Adapter-layer would silently keep the last write; fail
        // fast at construction instead.
        val slots = equipment.map { (slot, _) -> slot }
        require(slots.size == slots.toSet().size) {
            "MobSpec($id): duplicate equipment slot in $equipment"
        }
        // baseEntityType must be a category an adapter can render. The four
        // entity-side categories that map to live entities are MOB / NPC /
        // PROJECTILE / DISPLAY; mobs use the first three (PROJECTILE for
        // hostile entity types like shulker bullets registered as mobs).
        // EXPERIENCE_ORB / ITEM are world-spawned by other systems and not
        // valid base types for a [MobSpec].
        require(baseEntityType != EntityCategory.EXPERIENCE_ORB && baseEntityType != EntityCategory.ITEM) {
            "MobSpec($id).baseEntityType cannot be ${baseEntityType.name} — " +
                "use MOB / NPC / DISPLAY (PROJECTILE for entity-type hostile projectiles)."
        }
    }
}
