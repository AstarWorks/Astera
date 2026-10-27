package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.ISummonGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Server
import java.util.UUID

/** Heal, explode, lightning and summon ops, each backed by the existing Astera gateway (or Bukkit for the explosion itself). */
public class PaperSkillWorldEffectGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val health: () -> IPlayerHealthGateway?,
    private val lightning: () -> ILightningGateway?,
    private val summons: () -> ISummonGateway?,
    private val teams: () -> IMatchTeamLookup?,
) : ISkillWorldEffectGateway {
    private val source = HealthChangeSource.Other("skill-engine")

    override fun heal(caster: UUID, victim: UUID, amount: Double) {
        health()?.heal(PlayerId(victim), amount, source)
    }

    override fun resurrect(caster: UUID, victim: UUID, healthFraction: Double) {
        val gateway = health() ?: return
        val id = PlayerId(victim)
        val max = gateway.getMaxHealth(id) ?: return
        gateway.setHealth(id, (max * healthFraction.coerceIn(0.0, 1.0)).coerceAtLeast(1.0), source)
    }

    override fun explode(caster: UUID, at: Vec3, power: Double, breakBlocks: Boolean, incendiary: Boolean) {
        val owner = server.getEntity(caster) ?: return
        owner.world.createExplosion(Location(owner.world, at.x, at.y, at.z), power.toFloat(), incendiary, breakBlocks, owner)
    }

    override fun summon(caster: UUID, at: Vec3, entityType: String, count: Int, lifetimeTicks: Int) {
        val gateway = summons() ?: return
        val owner = server.getEntity(caster) ?: return
        val ownerId = PlayerId(caster)
        repeat(count.coerceAtLeast(0)) {
            val id = gateway.spawn(
                ISummonGateway.SummonRequest(entityType, WorldId(owner.world.name), at, ownerId, teams()?.teamOf(ownerId)),
            ) ?: return@repeat
            if (lifetimeTicks > 0) scheduler.runLaterTicks(ExecutionScope.Entity(id.uuid), lifetimeTicks.toLong()) { gateway.despawn(id) }
        }
    }

    override fun lightning(caster: UUID, at: Vec3, visualOnly: Boolean) {
        val owner = server.getEntity(caster) ?: return
        val world = owner.world
        if (visualOnly) {
            world.strikeLightningEffect(Location(world, at.x, at.y, at.z))
        } else {
            lightning()?.strike(WorldId(world.name), at, damageEntities = true)
        }
    }
}
