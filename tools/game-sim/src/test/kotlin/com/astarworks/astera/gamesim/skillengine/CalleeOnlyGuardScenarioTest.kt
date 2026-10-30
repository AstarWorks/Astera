package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.runtime.CastError
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Callee guard over the real shipped content: a skill that declares `frames_in` or a `required` param nobody values (frost-shard-burst)
 * is rejected on a standalone cast with the sealed [CastError.CalleeOnly] (never a silent no-op), while every skill that calls it
 * (frost-shard) still casts and plays it.
 */
class CalleeOnlyGuardScenarioTest : SkillEngineScenario() {
    @Test
    fun everyCalleeOnlySkillIsRejectedStandaloneAndEveryCallerStillCastsIt() {
        val problems = ArrayList<String>()
        val skillsDir = contentDir.resolve("skills")
        val composed = when (val c = SkillComposer(skillsDir).composeAll()) {
            is Result.Failure -> throw AssertionError("compose: ${c.error}")
            is Result.Success -> c.value
        }
        val defs = LinkedHashMap<String, SkillDef>()
        for ((id, v) in composed) {
            when (val d = decodeTree(id, v.tree, composed.keys.toSet())) {
                is Result.Failure -> problems += "$id: ${d.error}"
                is Result.Success -> defs[id] = d.value
            }
        }
        val callees = defs.filterValues { it.isCalleeOnly }.keys
        assertThat(callees).`as`("shipped callee-only skills").contains("frost-shard-burst")
        val yamls = Files.walk(skillsDir).use { s -> s.filter { it.toString().endsWith(".yaml") }.toList() }
        for (callee in callees) {
            val def = defs.getValue(callee)
            val compiled = (compileAgainst(def, defs) as? Result.Success)?.value
            if (compiled == null) { problems += "$callee: does not compile"; continue }
            val rec = GatewayRecorder()
            val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
            val request = CastRequest(compiled, rec.caster, WorldId("world"), Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0)
            val err = (player.start(request, compiled) as? Result.Failure)?.error
            if (err !is CastError.CalleeOnly || err.skillId != callee || (err.missingFrames + err.missingParams).isEmpty()) {
                problems += "$callee: standalone cast was not rejected with CalleeOnly naming a frame or param: $err"
            }
            val call = Regex("use:\\s*skill:" + Regex.escape(callee) + "(?![A-Za-z0-9_-])")
            val callers = yamls.filter { call.containsMatchIn(Files.readString(it)) }
                .map { it.parent.fileName.toString() }.filter { it != callee && defs[it]?.isCalleeOnly == false }.distinct()
            if (callers.isEmpty()) problems += "$callee: no castable caller found in content/skills"
            for (caller in callers) {
                when (val r = compileAgainst(defs.getValue(caller), defs)) {
                    is Result.Failure -> problems += "$caller: ${r.error}"
                    is Result.Success -> try { assertPlays(r.value) } catch (e: Throwable) { problems += "$caller: casting it failed: ${e.message}" }
                }
            }
        }
        assertThat(problems).isEmpty()
    }
}
