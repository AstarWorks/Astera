package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import java.io.File

/**
 * Gameplay-parity dump (SKL-17): every shipped skill cast by a scripted caster (feet at the origin, yaw 0, pitch 0) with a
 * dummy 5 blocks ahead and a wall plane 8 blocks ahead, recorded as what the skill DOES: sounds, damage, knockback, velocity,
 * movement of caster and dummy, statuses, block edits, cast phases, cooldown and resource cost. One JSON object per line, the
 * same vocabulary as the v1 reference dump (`tools/skill-convert/v1-dumper/V1GameplayDumpTest.kt.txt`) so
 * `tools/skill-convert/gameplay_diff.py` can compare the two. Nothing here changes behaviour; it only reads what the ports saw.
 */
object GameplayDump {
    const val TICKS = 120

    private fun r(d: Double): String {
        val v = Math.round(d * 1e3) / 1e3
        return (if (v == 0.0) 0.0 else v).toString()
    }

    private val itemsDir: File by lazy {
        File(requireNotNull(System.getProperty("astera.sim.contentDir")) { "astera.sim.contentDir is not set" }).resolve("items")
    }

    /** The item that binds [skill] (`skill_id: <skill>`), its id and its `resource_type` / `resource_cost_per_use`. */
    private fun itemOf(skill: String): Triple<String, String?, Int?>? {
        for (f in itemsDir.listFiles()!!.sortedBy { it.name }) {
            val t = f.readText()
            if (Regex("""skill_id:\s*${Regex.escape(skill)}\s*$""", RegexOption.MULTILINE).containsMatchIn(t)) {
                val id = Regex("""^id:\s*(\S+)""", RegexOption.MULTILINE).find(t)?.groupValues?.get(1) ?: return null
                val type = Regex("""^resource_type:\s*(\S+)""", RegexOption.MULTILINE).find(t)?.groupValues?.get(1)
                val cost = Regex("""^resource_cost_per_use:\s*(\d+)""", RegexOption.MULTILINE).find(t)?.groupValues?.get(1)?.toInt()
                return Triple(id, type, cost)
            }
        }
        return null
    }

    fun ids(): List<String> = VanillaDump.ids()

    /** The lines of one cast. */
    fun dump(id: String, ticks: Int = TICKS): List<String> {
        val lines = ArrayList<String>()
        val feet = Vec3.ZERO
        val sim = CastSim(mapOf(simVictim(1) to ahead(feet, 0.0, 5.0)), blockAtZ = (System.getenv("ASTERA_WALL_Z") ?: "8").toDouble(), floorY = 0.0)
        val item = itemOf(id)
        lines += "{\"e\":\"meta\",\"id\":\"$id\",\"item\":\"${item?.first}\"}"
        val meta = sim.catalog.get(id)?.def?.meta
        if (meta != null) {
            val type = meta.resourceType ?: item?.second
            val cost = meta.resourceCost ?: item?.third ?: 0
            lines += "{\"t\":0,\"e\":\"cost\",\"ammo\":${if (type == "ammo") cost else 0},\"energy\":${if (type == "energy") cost else 0}}"
            if (meta.cooldownTicks > 0 || meta.reloadTicks > 0) {
                lines += "{\"t\":0,\"e\":\"cooldown\",\"skill\":\"$id\",\"ticks\":${meta.cooldownTicks},\"reload\":${meta.reloadTicks}}"
            }
            if (meta.egoCost > 0) lines += "{\"t\":0,\"e\":\"egocost\",\"value\":${meta.egoCost}}"
        }
        val dummy = simVictim(1)
        val lastVel = hashMapOf("caster" to Vec3.ZERO, "dummy" to Vec3.ZERO)
        try {
            sim.start(id)
            for (i in 0 until ticks) {
                for ((n, uuid) in listOf("caster" to SIM_CASTER, "dummy" to dummy)) {
                    val p = if (uuid == SIM_CASTER) sim.casterFeet else sim.standing[uuid] ?: Vec3.ZERO
                    val v = sim.velocities[uuid] ?: Vec3.ZERO
                    lines += "{\"t\":$i,\"e\":\"pos\",\"who\":\"$n\",${sim.rel(p)},\"vx\":${r(v.x)},\"vy\":${r(v.y)},\"vz\":${r(v.z)}}"
                    if (lastVel.put(n, v) != v) lines += "{\"t\":$i,\"e\":\"vel\",\"who\":\"$n\",\"x\":${r(v.x)},\"y\":${r(v.y)},\"z\":${r(v.z)}}"
                }
                sim.step(i + 1)
            }
        } catch (e: Throwable) {
            lines += "{\"e\":\"error\",\"msg\":\"${((e::class.simpleName ?: "") + ": " + e.message.orEmpty()).take(200).replace("\"", "'").replace("\\", "/").replace("\n", " ")}\"}"
        }
        lines += sim.gp
        return lines
    }
}
