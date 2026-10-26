package com.astarworks.astera.persistence.adapter.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/** JDBC is confined to IO; withContext resumes on the caller's ExecutionScope dispatcher. */
internal suspend fun <T> ioTransaction(db: Database, block: JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { IoThread.run { blockingTransaction(db, block) } }
