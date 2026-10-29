package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprHost
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import java.util.concurrent.ConcurrentHashMap

/**
 * The ONE evaluator of `= expr` / `expr:` live cells, shared by the Paper live-value source and the game-sim recorder, so the two
 * compile with the same environment (the caster frame plus the skill's own instance ids, [RowScope.frameNames]) and serve the same
 * host functions ([SERVED_FUNCTIONS]); a cell Paper cannot evaluate fails in sim the same way.
 */
public class CellExprs {
    private val compiler = CelExprCompiler()
    private val compiled = ConcurrentHashMap<Pair<String, Set<String>>, CompiledExpr>()

    /** Evaluates the cell [name] (`= expr` or `expr:expr`); [host] is wrapped so a function outside [SERVED_FUNCTIONS] fails loudly. */
    public fun eval(name: String, scope: RowScope, variables: Map<String, ExprValue>, frames: FrameLookup, host: ExprHost): ExprValue {
        val source = sourceOf(name) ?: throw IllegalArgumentException("'$name' is not an expression cell")
        val expr = compiled.getOrPut(source to scope.frameNames) {
            val spec = V3Bindings.spec(null, ExprEnvSpec(frames = setOf(CASTER) + scope.frameNames))
            when (val r = compiler.compile(source, spec, null)) {
                is Result.Success -> r.value
                is Result.Failure -> throw IllegalArgumentException("'$source' does not compile: ${r.error.message}")
            }
        }
        val vars = LinkedHashMap(variables)
        var prefix = ""
        for (idx in scope.indices.take(V3Bindings.MAX_PARENT_DEPTH + 1)) {
            vars[prefix + "i"] = ExprValue.Num(idx.toDouble())
            prefix += "parent."
        }
        val env = ExprEnv(variables = vars, frames = frames, seed = scope.seed, castSeed = scope.castSeed, host = served(host))
        return expr.eval(env)
    }

    /** Wraps the host: only [SERVED_FUNCTIONS] reach it. */
    private class Served(private val inner: ExprHost) : ExprHost {
        override fun aimPoint(caster: ExprValue.Ref, range: Double): Vec3 = inner.aimPoint(caster, range)

        override fun lookDirection(caster: ExprValue.Ref): Vec3 = inner.lookDirection(caster)

        override fun stacks(entity: ExprValue.Ref, status: String): Double = inner.stacks(entity, status)

        override fun hasItem(entity: ExprValue.Ref, item: String): Boolean = inner.hasItem(entity, item)

        override fun nearestEnemyDist(from: Vec3): Double = inner.nearestEnemyDist(from)

        override fun enemyInCone(from: Vec3, dir: Vec3, range: Double, halfAngleDeg: Double): Boolean = inner.enemyInCone(from, dir, range, halfAngleDeg)
    }

    public companion object {
        public const val CASTER: String = "caster"

        /** [inner] behind the one wrapper both Paper and the sim evaluate through. */
        public fun served(inner: ExprHost): ExprHost = Served(inner)

        /** The host functions a live cell may call; every other [ExprHost] member is rejected. */
        public val SERVED_FUNCTIONS: Set<String> = setOf("aim_point", "look", "stacks", "has_item", "nearest_enemy_dist", "enemy_in_cone")

        /** The expression text of a live cell, or null when [name] is not an expression cell. */
        public fun sourceOf(name: String): String? = when {
            name.startsWith("= ") -> name.removePrefix("= ")
            name.startsWith("expr:") -> name.removePrefix("expr:")
            else -> null
        }
    }
}
