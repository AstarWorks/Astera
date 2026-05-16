package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.effect.ParticleSpec
import com.astarworks.astera.domain.model.effect.SoundSpec
import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.world.BlockType
import com.astarworks.astera.domain.model.world.WorldId

/**
 * Outbound port: side-effecting operations on the world (blocks, effects).
 *
 * Phrased entirely in domain types — [Vec3], [BlockType], [WorldId],
 * [ParticleSpec], [SoundSpec]. The Paper adapter resolves these to Bukkit
 * `Location` / `Material` / `Particle` / `Sound`.
 *
 * Phase 2 mid lands the actual implementation; ports are declared now so
 * use cases written against them can be unit-tested with `FakeWorldGateway`
 * (test-fixtures) ahead of the Paper plumbing.
 */
public interface IWorldGateway {

    public fun placeBlock(world: WorldId, at: Vec3, type: BlockType)
    public fun removeBlock(world: WorldId, at: Vec3)
    public fun blockAt(world: WorldId, at: Vec3): BlockType?

    public fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec)
    public fun playSound(world: WorldId, at: Vec3, sound: SoundSpec)
}
