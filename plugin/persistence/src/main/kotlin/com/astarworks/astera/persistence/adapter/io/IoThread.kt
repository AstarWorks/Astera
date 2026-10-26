package com.astarworks.astera.persistence.adapter.io

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Runtime guard for blocking storage entry points, including preload and bootstrap. */
internal object IoThread {
    private val active = ThreadLocal.withInitial { false }

    fun <T> run(block: () -> T): T {
        val previous = active.get()
        active.set(true)
        return try { block() } finally { active.set(previous) }
    }

    fun check() { check(active.get()) { "Blocking persistence requires IO scope" } }
}

internal fun <T> blockingTransaction(db: Database, block: JdbcTransaction.() -> T): T {
    IoThread.check()
    return transaction(db) { block() }
}
