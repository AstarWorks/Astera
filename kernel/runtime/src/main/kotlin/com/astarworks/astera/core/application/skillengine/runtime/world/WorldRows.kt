package com.astarworks.astera.core.application.skillengine.runtime.world

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.world.BreakAreaSpec
import com.astarworks.astera.core.domain.skillengine.world.PlaceShapeSpec
import com.astarworks.astera.core.domain.skillengine.world.WorldAim
import org.joml.Vector3d
import java.util.UUID

/** Plays an `Action.World` row: the player resolves [origin] / [forward], this picks the gateway call. */
public object WorldRows {
    public fun execute(world: ISkillWorldGateway, caster: UUID, row: Action.World, origin: Vector3d, forward: Vector3d) {
        val at = Vec3(origin.x, origin.y, origin.z)
        when (row.op.value) {
            "break_area" -> world.breakArea(caster, known<BreakAreaSpec>(row, "spec"), WorldAim(at, Vec3(forward.x, forward.y, forward.z)))
            "place_shape" -> world.placeShape(caster, known<PlaceShapeSpec>(row, "spec"), WorldAim(at, Vec3(forward.x, forward.y, forward.z)))
            "explode" -> world.explode(caster, at, known(row, "power"), known(row, "break_blocks"), known(row, "fire"))
            "lightning" -> world.lightning(caster, at, known(row, "visual_only"))
            else -> error("unknown world op '${row.op.value}'")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> known(row: Action.World, key: String): T =
        ((row.payload[key] as? Val.Known<*>) ?: error("world op '${row.op.value}' payload '$key' is not a known value")).value as T
}
