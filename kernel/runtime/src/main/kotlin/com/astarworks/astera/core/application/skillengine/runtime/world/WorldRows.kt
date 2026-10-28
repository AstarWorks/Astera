package com.astarworks.astera.core.application.skillengine.runtime.world

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.world.BreakAreaSpec
import com.astarworks.astera.core.domain.skillengine.world.PlaceShapeSpec
import com.astarworks.astera.core.domain.skillengine.world.WorldAim
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import java.util.UUID

/** Plays an `Action.World` row: the player resolves [origin] / [forward], this picks the gateway call. */
public object WorldRows {
    public fun execute(world: ISkillWorldGateway, cast: CastRef, row: Action.World, origin: Vector3d, forward: Vector3d, eye: (() -> Vector3d)? = null, number: (Val<Any>) -> Double) {
        val at = Vec3(origin.x, origin.y, origin.z)
        fun aim(): WorldAim {
            val e = if (row.op.value == "place_shape") eye?.invoke() ?: origin else origin
            return WorldAim(at, Vec3(forward.x, forward.y, forward.z), Vec3(e.x, e.y, e.z))
        }
        when (row.op.value) {
            "break_area" -> world.breakArea(cast, known<BreakAreaSpec>(row, "spec"), aim())
            "place_shape" -> world.placeShape(cast, known<PlaceShapeSpec>(row, "spec"), aim())
            "explode" -> world.explode(cast, at, number(cell(row, "power")), known(row, "break_blocks"), known(row, "fire"))
            "lightning" -> world.lightning(cast, at, known(row, "visual_only"))
            else -> error("unknown world op '${row.op.value}'")
        }
    }

    private fun cell(row: Action.World, key: String): Val<Any> = row.payload[key] ?: error("world op '${row.op.value}' payload '$key' is missing")

    @Suppress("UNCHECKED_CAST")
    private fun <T> known(row: Action.World, key: String): T =
        ((row.payload[key] as? Val.Known<*>) ?: error("world op '${row.op.value}' payload '$key' is not a known value")).value as T
}
