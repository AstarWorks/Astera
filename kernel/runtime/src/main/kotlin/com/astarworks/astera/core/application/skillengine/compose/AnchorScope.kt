package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.table.InstanceId

/** Resolves `anchor: ball` to this occurrence's InstanceId and basis class (S/T/L). Stub: W1-07 implements it. */
public interface AnchorScope {
    public fun resolve(name: String): ResolvedAnchor?
}

public data class ResolvedAnchor(val instance: InstanceId, val basis: ExprClass)
