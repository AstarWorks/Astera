package com.astarworks.astera.core.adapter.paper.observability

import com.astarworks.astera.core.application.port.outbound.admin.HealthState
import com.astarworks.astera.core.application.port.outbound.admin.IServerHealthCheck
import com.astarworks.astera.core.application.port.outbound.observability.IMetricsRegistry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal HTTP endpoint serving Prometheus scrape requests at `/metrics`.
 *
 * Wave _ops (Phase 1.5): we deliberately use the JDK-bundled
 * [com.sun.net.httpserver.HttpServer] to avoid pulling Jetty / Netty into the
 * plugin shadow jar. A single-thread executor keeps request handling off the
 * JDK default (which is `null` and therefore synchronous on the accept thread).
 *
 * Lifecycle:
 *  - [start] binds the configured port and begins serving.
 *  - [stop] releases the port immediately (`stop(delay = 0)`).
 *
 * Pass `port = 0` to bind to an ephemeral port (useful for tests). Query
 * [boundPort] after [start] to read the actual port.
 */
public class HttpMetricsServer(
    private val port: Int,
    private val registry: IMetricsRegistry,
    private val healthCheck: IServerHealthCheck? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    /** The port the embedded server is actually bound to (valid after [start]). */
    public val boundPort: Int
        get() = server?.address?.port ?: port

    /** Whether [start] has been called and [stop] has not. */
    public val isRunning: Boolean
        get() = server != null

    public fun start() {
        check(server == null) { "HttpMetricsServer already started on port $port" }

        val bound = HttpServer.create(InetSocketAddress(port), 0)
        bound.createContext("/metrics") { exchange -> handleMetrics(exchange) }
        // Wave AF 5operator-observability (ADR-0142): k8s probe endpoints.
        // Mapping per IServerHealthCheck KDoc (Wave 5t / ADR-0086):
        //   /healthz → overall == Healthy   (liveness — restart pod on failure)
        //   /readyz  → overall != Down      (readiness — drain traffic on failure)
        // When healthCheck is null (unit-test / pre-Wave-5t boot), both
        // endpoints return 200 OK with body "no health check registered" so
        // probes still pass and the pod doesn't restart-loop.
        bound.createContext("/healthz") { exchange -> handleHealthz(exchange) }
        bound.createContext("/readyz") { exchange -> handleReadyz(exchange) }
        bound.createContext("/") { exchange -> handleNotFound(exchange) }

        val exec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "astera-metrics-http").apply { isDaemon = true }
        }
        bound.executor = exec
        bound.start()

        server = bound
        executor = exec
        log.info("Astera metrics endpoint listening on :{}/metrics", bound.address.port)
    }

    public fun stop() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    private fun handleMetrics(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, -1L)
                return@use
            }
            val body = registry.scrape().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders["Content-Type"] = listOf("text/plain; version=0.0.4; charset=utf-8")
            exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }

    private fun handleNotFound(exchange: HttpExchange) {
        exchange.use {
            val body = "Not Found: ${exchange.requestURI.path}\n".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders["Content-Type"] = listOf("text/plain; charset=utf-8")
            exchange.sendResponseHeaders(HTTP_NOT_FOUND, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }

    private fun handleHealthz(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, -1L)
                return@use
            }
            val (status, body) = when (val check = healthCheck) {
                null -> HTTP_OK to "no health check registered\n"
                else -> {
                    val report = check.snapshot()
                    val ok = report.overall == HealthState.Healthy
                    val httpCode = if (ok) HTTP_OK else HTTP_SERVICE_UNAVAILABLE
                    httpCode to "${report.overall}: ${report.individuals}\n"
                }
            }
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders["Content-Type"] = listOf("text/plain; charset=utf-8")
            exchange.sendResponseHeaders(status, bodyBytes.size.toLong())
            exchange.responseBody.write(bodyBytes)
        }
    }

    private fun handleReadyz(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, -1L)
                return@use
            }
            val (status, body) = when (val check = healthCheck) {
                null -> HTTP_OK to "no health check registered\n"
                else -> {
                    val report = check.snapshot()
                    val ready = report.overall != HealthState.Down
                    val httpCode = if (ready) HTTP_OK else HTTP_SERVICE_UNAVAILABLE
                    httpCode to "${report.overall}: ${report.individuals}\n"
                }
            }
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders["Content-Type"] = listOf("text/plain; charset=utf-8")
            exchange.sendResponseHeaders(status, bodyBytes.size.toLong())
            exchange.responseBody.write(bodyBytes)
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
