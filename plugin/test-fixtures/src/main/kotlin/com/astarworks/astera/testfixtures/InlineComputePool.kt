package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IComputePool
import com.astarworks.astera.core.application.port.outbound.WorkClass
import java.util.concurrent.CompletableFuture

public class InlineComputePool : IComputePool {
    override fun <T> forkJoin(chunks: List<() -> T>): List<T> = chunks.map { it() }
    override fun <T> async(cls: WorkClass, block: () -> T): CompletableFuture<T> =
        try { CompletableFuture.completedFuture(block()) }
        catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
}
