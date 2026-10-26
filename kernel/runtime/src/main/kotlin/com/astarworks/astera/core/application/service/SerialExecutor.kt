package com.astarworks.astera.core.application.service

import java.util.ArrayDeque
import java.util.concurrent.Executor

/** One drain per mailbox, including when the backing executor runs inline. */
public class SerialExecutor(
    private val executor: Executor,
    private val onIdle: () -> Unit = {},
) : Executor {
    private val queue = ArrayDeque<Runnable>()
    private var running = false

    public val isIdle: Boolean get() = synchronized(queue) { !running && queue.isEmpty() }

    override fun execute(command: Runnable) {
        synchronized(queue) {
            queue.addLast(command)
            if (running) return
            running = true
        }
        try {
            executor.execute(::drain)
        } catch (failure: Throwable) {
            synchronized(queue) { running = false; queue.clear() }
            throw failure
        }
    }

    private fun drain() {
        while (true) {
            val task = synchronized(queue) {
                if (queue.isEmpty()) { running = false; null } else queue.removeFirst()
            }
            if (task == null) { onIdle(); return }
            // A bad subscriber must not strand the rest of the mailbox.
            try { task.run() } catch (failure: Throwable) {
                Thread.currentThread().uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), failure)
            }
        }
    }
}
