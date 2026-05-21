package com.astarworks.astera.core.domain.entity

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Declarative description of "what to spawn" — domain-layer input to
 * [com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner.spawn].
 *
 * The five variants intentionally mirror the categories in [EntityCategory] so
 * the router can dispatch on type without reflection. Mob vs NPC vs Display vs
 * Projectile vs CitizensNpc is **not** a vendor distinction — it's a domain
 * distinction (different gameplay roles), and the spawner picks the right
 * backend adapter underneath.
 */
public sealed interface SpawnSpec {

    /**
     * A vanilla mob (or any registered entity type) with optional name and
     * AI suppression. [typeKey] is a vendor-neutral catalog key — Paper
     * adapters resolve via `EntityType.valueOf(...)` with case-insensitive
     * matching after stripping a `minecraft:` prefix.
     */
    public data class Mob(
        public val typeKey: String,
        public val customNameKey: MessageKey? = null,
        public val noAi: Boolean = false,
    ) : SpawnSpec {
        init {
            require(typeKey.isNotBlank()) { "Mob.typeKey must not be blank" }
        }
    }

    /**
     * Minimal NPC backed by an `ArmorStand`. Visible / invisible toggle plus
     * optional held-item display. Suitable for shop vendors, quest givers
     * and decorative props when Citizens is not installed.
     */
    public data class ArmorStandNpc(
        public val displayName: MessageKey?,
        public val visible: Boolean = false,
        public val itemInHand: ItemId? = null,
    ) : SpawnSpec

    /**
     * Display-entity hologram. Routes to the same Minimal backend as
     * [ArmorStandNpc] but uses the 1.19.4+ Display family for cleaner visuals.
     */
    public data class DisplayNpc(public val kind: HologramKind) : SpawnSpec

    /**
     * Citizens-backed NPC. Requires the Citizens plugin to be present at
     * runtime — when absent the spawner falls back to [ArmorStandNpc] /
     * [DisplayNpc] for compatible specs and returns [SpawnError.NoSpawnableBackend]
     * otherwise.
     */
    public data class CitizensNpc(
        public val typeKey: String,
        public val displayName: MessageKey?,
        public val skin: NpcSkin? = null,
        public val traits: List<NpcTraitSpec> = emptyList(),
    ) : SpawnSpec {
        init {
            require(typeKey.isNotBlank()) { "CitizensNpc.typeKey must not be blank" }
        }
    }

    /**
     * Projectile entity (arrow / fireball / custom) with initial velocity.
     * Use [shooter] when the projectile should credit a player for damage
     * attribution; null produces a "world-shot" projectile.
     */
    public data class Projectile(
        public val typeKey: String,
        public val velocity: Vec3,
        public val shooter: PlayerId?,
    ) : SpawnSpec {
        init {
            require(typeKey.isNotBlank()) { "Projectile.typeKey must not be blank" }
        }
    }
}
