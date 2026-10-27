package com.astarworks.astera.core.application.skillengine.expr.cel

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprError
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import dev.cel.common.CelException
import dev.cel.common.CelIssue
import dev.cel.common.types.CelType
import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompiler
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.CelRuntime
import dev.cel.runtime.CelRuntimeFactory
import java.util.concurrent.ConcurrentHashMap

/** CEL implementation of [ExprCompiler] (ADR-0299). Thread-safe; compilers are cached per [ExprEnvSpec]. */
public class CelExprCompiler : ExprCompiler {
    private val runtime: CelRuntime = CelRuntimeFactory.standardCelRuntimeBuilder()
        .addFunctionBindings(CelFunctions.bindings())
        .build()

    private val compilers = ConcurrentHashMap<ExprEnvSpec, CelCompiler>()

    private fun compilerFor(spec: ExprEnvSpec): CelCompiler = compilers.getOrPut(spec) {
        val b = CelCompilerFactory.standardCelCompilerBuilder()
        for ((name, type) in spec.variables) b.addVar(name, celType(type))
        for (frame in spec.frames) b.addVar(frame, CelFunctions.REF)
        b.addFunctionDeclarations(CelFunctions.declarations())
        b.build()
    }

    override fun compile(source: String, env: ExprEnvSpec): Result<CompiledExpr, ExprError> {
        val compiler = compilerFor(env)
        val parsed = compiler.parse(normalise(source))
        if (parsed.hasError()) return Result.Failure(error(parsed.errors.first(), syntax = true))
        val checked = compiler.check(parsed.ast)
        if (checked.hasError()) return Result.Failure(error(checked.errors.first(), syntax = false))
        return Result.Success(CelCompiled(source, runtime.createProgram(checked.ast), env.frames))
    }

    private fun error(issue: CelIssue, syntax: Boolean): ExprError {
        val col = issue.sourceLocation.column + 1
        return if (syntax) ExprError.Syntax(issue.message, col) else ExprError.Type(issue.message, col)
    }

    private class CelCompiled(
        override val source: String,
        private val program: CelRuntime.Program,
        frames: Set<String>,
    ) : CompiledExpr {
        private val frameRefs: Map<String, Any> = frames.associateWith { ExprValue.Ref(it) }

        override fun eval(env: ExprEnv): ExprValue {
            val activation = HashMap<String, Any?>(env.variables.size + frameRefs.size)
            activation.putAll(frameRefs)
            for ((k, v) in env.variables) activation[k] = toRaw(v)
            CelFunctions.beginEval(env)
            try {
                return fromRaw(program.eval(activation))
            } catch (e: ExprEvalException) {
                throw e
            } catch (e: CelException) {
                throw ExprEvalException("'$source': ${e.cause?.message ?: e.message}", e)
            } finally {
                CelFunctions.endEval()
            }
        }
    }

    private companion object {
        fun celType(t: ExprType): CelType = when (t) {
            ExprType.NUM -> SimpleType.DOUBLE
            ExprType.VEC3 -> CelFunctions.VEC3
            ExprType.QUAT -> CelFunctions.QUAT
            ExprType.BOOL -> SimpleType.BOOL
            ExprType.STR -> SimpleType.STRING
            ExprType.REF -> CelFunctions.REF
        }

        fun toRaw(v: ExprValue): Any = when (v) {
            is ExprValue.Num -> v.value
            is ExprValue.Vec3 -> v.value
            is ExprValue.Quat -> v.value
            is ExprValue.Bool -> v.value
            is ExprValue.Str -> v.value
            is ExprValue.Ref -> v
        }

        fun fromRaw(o: Any?): ExprValue = when (o) {
            is Double -> ExprValue.Num(o)
            is Number -> ExprValue.Num(o.toDouble())
            is Boolean -> ExprValue.Bool(o)
            is String -> ExprValue.Str(o)
            is Vec3 -> ExprValue.Vec3(o)
            is Quaternion -> ExprValue.Quat(o)
            is ExprValue.Ref -> o
            else -> throw ExprEvalException("unsupported expression result: $o")
        }

        /**
         * Blank-pads the leading `=` and rewrites `${param}` to ` param` (same length, so error columns stay
         * correct) before handing the text to CEL.
         */
        fun normalise(source: String): String {
            val sb = StringBuilder(source)
            val lead = source.indexOfFirst { !it.isWhitespace() }
            if (lead >= 0 && source[lead] == '=') sb.setCharAt(lead, ' ')
            var i = 0
            while (i < sb.length - 1) {
                if (sb[i] == '$' && sb[i + 1] == '{') {
                    val end = sb.indexOf("}", i)
                    if (end > 0) {
                        sb.setCharAt(i, ' ')
                        sb.setCharAt(i + 1, ' ')
                        sb.setCharAt(end, ' ')
                        i = end
                    }
                }
                i++
            }
            return sb.toString()
        }
    }
}
