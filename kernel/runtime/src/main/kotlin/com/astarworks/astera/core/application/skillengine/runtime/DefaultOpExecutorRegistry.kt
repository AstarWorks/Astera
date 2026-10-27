package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/** Executors keyed by the exact op class; no central `when` (ADR-0301). A later registration replaces an earlier one. */
public class DefaultOpExecutorRegistry : OpExecutorRegistry {
    private val byClass = ConcurrentHashMap<KClass<*>, OpExecutor<*>>()

    override fun <O : SkillOp> register(executor: OpExecutor<O>) {
        byClass[executor.opClass] = executor
    }

    @Suppress("UNCHECKED_CAST")
    override fun <O : SkillOp> find(op: O): OpExecutor<O>? = byClass[op::class] as OpExecutor<O>?

    public fun registerAll(executors: Iterable<OpExecutor<*>>) {
        for (e in executors) byClass[e.opClass] = e
    }

    public fun registeredClasses(): Set<KClass<*>> = byClass.keys.toSet()
}
