package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.IWorldGateway
import com.astarworks.astera.domain.model.effect.ParticleSpec
import com.astarworks.astera.domain.model.effect.SoundSpec
import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.world.BlockType
import com.astarworks.astera.domain.model.world.WorldId

/**
 * In-memory [IWorldGateway] for tests. Each operation records its arguments
 * for assertion. Block state is kept in a single map keyed by (world, position).
 */
public class FakeWorldGateway : IWorldGateway {

    public data class Placement(val world: WorldId, val at: Vec3, val type: BlockType)
    public data class Removal(val world: WorldId, val at: Vec3)
    public data class ParticleEmission(val world: WorldId, val at: Vec3, val particle: ParticleSpec)
    public data class SoundEmission(val world: WorldId, val at: Vec3, val sound: SoundSpec)

    public val placements: MutableList<Placement> = mutableListOf()
    public val removals: MutableList<Removal> = mutableListOf()
    public val particles: MutableList<ParticleEmission> = mutableListOf()
    public val sounds: MutableList<SoundEmission> = mutableListOf()

    private val blocks: MutableMap<Pair<WorldId, Vec3>, BlockType> = mutableMapOf()

    override fun placeBlock(world: WorldId, at: Vec3, type: BlockType) {
        blocks[world to at] = type
        placements += Placement(world, at, type)
    }

    override fun removeBlock(world: WorldId, at: Vec3) {
        blocks.remove(world to at)
        removals += Removal(world, at)
    }

    override fun blockAt(world: WorldId, at: Vec3): BlockType? = blocks[world to at]

    override fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec) {
        particles += ParticleEmission(world, at, particle)
    }

    override fun playSound(world: WorldId, at: Vec3, sound: SoundSpec) {
        sounds += SoundEmission(world, at, sound)
    }
}
