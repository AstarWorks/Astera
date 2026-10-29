package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.ISkillTablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.modifier.ModifierStack
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/** Everything fixed at cast start (modifiers are snapshotted here, F-12). */
public data class CastRequest(
    val skill: CompiledSkill,
    val caster: UUID,
    /** The world the caster stands in; every sound, particle and entity query of the cast is bound to it. */
    val world: WorldId,
    val casterFeet: Vec3,
    val casterYawDeg: Double,
    val casterPitchDeg: Double,
    val seed: Long,
    val modifiers: ModifierStack = ModifierStack.EMPTY,
    val startTick: Int,
)

public sealed interface CastError {
    public data class FrameSetup(val reason: String) : CastError
    public data class ExprFailed(val path: String, val reason: String) : CastError
}

/** Per-caster facade over [ISkillTablePlayer]: the platform starts, signals and cancels casts by caster, not by handle. */
public interface CastRunner {
    public fun start(request: CastRequest): Result<CastHandle, CastError>

    /**
     * Deliver the input event [name] (`release`, `charge`) to every running cast of [caster];
     * returns how many casts received it (0 is normal: most clicks end no hold). [attacker] binds `event.attacker`
     * (the `hurt` event).
     */
    public fun raiseAllOf(caster: UUID, name: String, tick: Int, attacker: UUID? = null): Int

    /** F2: true when a running cast of [caster] would absorb the `hurt` event of [attacker] now (see [ISkillTablePlayer.absorbs]); delivers nothing. */
    public fun absorbsAnyOf(caster: UUID, name: String, tick: Int, attacker: UUID?): Boolean

    public fun cancel(cast: CastHandle, scope: CancelScope = CancelScope.Cast)

    public fun isRunning(cast: CastHandle): Boolean

    /** Cancels every running cast of [caster] (quit, death, weapon swap, world change); returns how many ended. */
    public fun cancelAllOf(caster: UUID): Int

    /** Cancels every running cast (plugin disable, reload). */
    public fun cancelAll(): Int

    /** Advance every running cast by one server tick. Called once per tick by the platform. */
    public fun tick(currentTick: Int)
}

/** [CastRunner] over the table player; remembers which caster owns which handle. */
public class DefaultCastRunner(private val player: ISkillTablePlayer) : CastRunner {
    private val owners = HashMap<CastHandle, UUID>()
    private val log = org.slf4j.LoggerFactory.getLogger(DefaultCastRunner::class.java)

    override fun start(request: CastRequest): Result<CastHandle, CastError> {
        val started = player.start(request, request.skill)
        if (started is Result.Success) owners[started.value] = request.caster
        return started
    }

    override fun raiseAllOf(caster: UUID, name: String, tick: Int, attacker: UUID?): Int {
        val mine = ownedLive(caster)
        mine.forEach { player.deliver(it, RuntimeEvent(EventPath(name), tick, null, null, null, attacker)) }
        return mine.size
    }

    override fun absorbsAnyOf(caster: UUID, name: String, tick: Int, attacker: UUID?): Boolean =
        ownedLive(caster).any { player.absorbs(it, RuntimeEvent(EventPath(name), tick, null, null, null, attacker), tick) }

    override fun cancel(cast: CastHandle, scope: CancelScope) {
        player.cancel(cast, scope)
    }

    override fun isRunning(cast: CastHandle): Boolean = cast in player.live()

    override fun cancelAllOf(caster: UUID): Int {
        val mine = ownedLive(caster)
        mine.forEach { player.cancel(it, CancelScope.Cast) }
        return mine.size
    }

    override fun cancelAll(): Int {
        val all = player.live()
        // one cast failing to wind down must not leave the rest running (nor throw out of a MatchEnded handler): log it loudly, carry on
        all.forEach { cast ->
            try {
                player.cancel(cast, CancelScope.Cast)
            } catch (t: Exception) {
                log.error("skill engine: cancelling cast {} failed", cast.value, t)
            }
        }
        return all.size
    }

    override fun tick(currentTick: Int) {
        player.tick(currentTick)
        val live = player.live().toSet()
        owners.keys.retainAll(live)
    }

    private fun ownedLive(caster: UUID): List<CastHandle> {
        val live = player.live().toSet()
        owners.keys.retainAll(live)
        return owners.filterValues { it == caster }.keys.toList()
    }
}
