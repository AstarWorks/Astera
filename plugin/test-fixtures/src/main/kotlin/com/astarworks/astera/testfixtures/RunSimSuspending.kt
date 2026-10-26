package com.astarworks.astera.testfixtures

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Drives suspending command dispatch using virtual time, without starting threads. */
public fun <T> runSimSuspending(scheduler: FakeScheduler, block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result }
    })
    scheduler.advanceTicks(0)
    repeat(1_200) {
        outcome?.let { return it.getOrThrow() }
        scheduler.advanceTicks(1)
    }
    return checkNotNull(outcome) { "Simulated command did not finish within 1200 ticks" }.getOrThrow()
}
