package com.astarworks.astera.core.application.port.outbound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Start an async operation and resume its continuations on the declared game owner.
 *
 * The block runs undispatched up to its first real suspension, so a chain of in-memory ports (which never suspend)
 * completes synchronously exactly as before, and only a JDBC hop (`withContext(Dispatchers.IO)`) leaves the
 * caller's thread. Everything after that hop resumes on [scope]'s dispatcher.
 */
public fun IScheduler.launch(scope: ExecutionScope, block: suspend CoroutineScope.() -> Unit): Job =
    CoroutineScope(dispatcher(scope)).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED, block = block)

/**
 * Fire-and-forget work on the Io scope for callers that hold no [IScheduler] and need no result back on a game
 * thread (event-bus listeners persisting a record, for example). Failures are logged, never rethrown.
 */
public object IoScope {
    private val log = org.slf4j.LoggerFactory.getLogger(IoScope::class.java)
    private val scope = CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, ex -> log.error("Io task failed", ex) },
    )

    public fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}
