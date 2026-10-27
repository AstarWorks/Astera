package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import kotlin.reflect.KClass

/** What an executor may touch while running one op of one cast. */
public interface OpContext {
    public val cast: CastId
    public val tree: FrameTree
    public val values: ValueResolver
    public val ports: SkillRuntimePorts
    public fun raise(event: String)

    /** Run nested ops (e.g. `on_hit`) in the current scope. */
    public fun run(ops: List<SkillOp>)
}

/** Executes the ops of ONE family. Registered per op class; no central `when`. */
public interface OpExecutor<O : SkillOp> {
    public val opClass: KClass<O>
    public fun execute(op: O, ctx: OpContext)
}

public interface OpExecutorRegistry {
    public fun <O : SkillOp> register(executor: OpExecutor<O>)
    public fun <O : SkillOp> find(op: O): OpExecutor<O>?
}
