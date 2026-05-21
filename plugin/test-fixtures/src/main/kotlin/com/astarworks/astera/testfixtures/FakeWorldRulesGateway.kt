package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldRulesGateway
import com.astarworks.astera.core.domain.world.WorldDifficulty
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Cross-plugin Fake for [IWorldRulesGateway]. Wraps the in-memory impl and
 * records every setter call into a [calls] list for assertions.
 *
 * Wave 5n (ADR-0080).
 */
public class FakeWorldRulesGateway : IWorldRulesGateway {

    public sealed interface Call {
        public data class SetDifficulty(val world: WorldId, val difficulty: WorldDifficulty) : Call
        public data class SetKeepInventory(val world: WorldId, val keep: Boolean) : Call
        public data class SetDoMobSpawning(val world: WorldId, val enabled: Boolean) : Call
        public data class SetDoFireTick(val world: WorldId, val enabled: Boolean) : Call
        public data class SetDoWeatherCycle(val world: WorldId, val enabled: Boolean) : Call
        public data class SetDoDaylightCycle(val world: WorldId, val enabled: Boolean) : Call
        public data class SetPvP(val world: WorldId, val enabled: Boolean) : Call
    }

    private val delegate: InMemoryWorldRulesGateway = InMemoryWorldRulesGateway()

    public val calls: MutableList<Call> = mutableListOf()

    override fun setDifficulty(world: WorldId, difficulty: WorldDifficulty) {
        calls += Call.SetDifficulty(world, difficulty); delegate.setDifficulty(world, difficulty)
    }

    override fun setKeepInventoryOnDeath(world: WorldId, keep: Boolean) {
        calls += Call.SetKeepInventory(world, keep); delegate.setKeepInventoryOnDeath(world, keep)
    }

    override fun setDoMobSpawning(world: WorldId, enabled: Boolean) {
        calls += Call.SetDoMobSpawning(world, enabled); delegate.setDoMobSpawning(world, enabled)
    }

    override fun setDoFireTick(world: WorldId, enabled: Boolean) {
        calls += Call.SetDoFireTick(world, enabled); delegate.setDoFireTick(world, enabled)
    }

    override fun setDoWeatherCycle(world: WorldId, enabled: Boolean) {
        calls += Call.SetDoWeatherCycle(world, enabled); delegate.setDoWeatherCycle(world, enabled)
    }

    override fun setDoDaylightCycle(world: WorldId, enabled: Boolean) {
        calls += Call.SetDoDaylightCycle(world, enabled); delegate.setDoDaylightCycle(world, enabled)
    }

    override fun setPvP(world: WorldId, enabled: Boolean) {
        calls += Call.SetPvP(world, enabled); delegate.setPvP(world, enabled)
    }

    override fun getDifficulty(world: WorldId): WorldDifficulty? = delegate.getDifficulty(world)
    override fun isKeepInventoryOnDeath(world: WorldId): Boolean = delegate.isKeepInventoryOnDeath(world)
    override fun isDoMobSpawning(world: WorldId): Boolean = delegate.isDoMobSpawning(world)
    override fun isDoFireTick(world: WorldId): Boolean = delegate.isDoFireTick(world)
    override fun isDoWeatherCycle(world: WorldId): Boolean = delegate.isDoWeatherCycle(world)
    override fun isDoDaylightCycle(world: WorldId): Boolean = delegate.isDoDaylightCycle(world)
    override fun isPvP(world: WorldId): Boolean = delegate.isPvP(world)
}
