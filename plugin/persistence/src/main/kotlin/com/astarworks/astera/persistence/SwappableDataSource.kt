package com.astarworks.astera.persistence

import com.zaxxer.hikari.HikariDataSource
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * The single [DataSource] the Exposed `Database` and every repository are bound to (ADR-0311 P3). `--reconnect`
 * replaces the pool behind it, so repositories already handed to Core or to a feature plugin keep working without
 * being rebuilt: new connections come from the new pool the moment [swap] returns, connections already checked out
 * of the old pool finish on it.
 */
internal class SwappableDataSource(initial: HikariDataSource) : DataSource {

    @Volatile
    var current: HikariDataSource = initial
        private set

    /** Makes [next] serve every new connection and returns the pool it replaced (to be drained and closed). */
    @Synchronized
    fun swap(next: HikariDataSource): HikariDataSource {
        val previous = current
        current = next
        return previous
    }

    override fun getConnection(): Connection = current.connection
    override fun getConnection(username: String?, password: String?): Connection = current.getConnection(username, password)
    override fun getLogWriter(): PrintWriter? = current.logWriter
    override fun setLogWriter(out: PrintWriter?) { current.logWriter = out }
    override fun setLoginTimeout(seconds: Int) { current.loginTimeout = seconds }
    override fun getLoginTimeout(): Int = current.loginTimeout
    override fun getParentLogger(): Logger = current.parentLogger
    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else current.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this) || current.isWrapperFor(iface)
}
