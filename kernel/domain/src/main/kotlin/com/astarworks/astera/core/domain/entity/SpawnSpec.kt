package com.astarworks.astera.core.domain.entity

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/** Declarative input to the entity spawner (ADR-0264). */
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

    /** Region-owned Display body with an Interaction hitbox. */
    public data class DisplayNpc(
        public val kind: HologramKind,
        public val yaw: Float = 0f,
        public val pitch: Float = 0f,
    ) : SpawnSpec

    /** Packet-only player body; signed textures are supplied by content. */
    public data class PlayerNpc(
        public val profileName: String,
        public val skin: NpcSkin,
        public val yaw: Float = 0f,
        public val pitch: Float = 0f,
    ) : SpawnSpec {
        init { require(profileName.matches(Regex("[A-Za-z0-9_]{1,16}"))) }
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
