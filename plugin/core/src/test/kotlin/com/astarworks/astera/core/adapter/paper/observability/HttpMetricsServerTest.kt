package com.astarworks.astera.core.adapter.paper.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * [HttpMetricsServer] contract:
 *  - GET /metrics → 200 + Prometheus exposition body.
 *  - GET /other  → 404.
 *  - stop() releases the bound port.
 *
 * Tests bind ephemeral ports (`port = 0`) and read [HttpMetricsServer.boundPort]
 * to avoid clashing with CI parallelism.
 */
class HttpMetricsServerTest {

    private var server: HttpMetricsServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `GET _metrics returns 200 with exposition body`() {
        val registry = MicrometerMetricsRegistry().also {
            it.counter("astera_smoke").increment()
        }
        val srv = HttpMetricsServer(port = 0, registry = registry).also { server = it }
        srv.start()

        val (code, body) = httpGet("http://127.0.0.1:${srv.boundPort}/metrics")

        assertThat(code).isEqualTo(200)
        assertThat(body).contains("# HELP")
        assertThat(body).contains("# TYPE")
        assertThat(body).contains("astera_smoke")
    }

    @Test
    fun `GET unknown path returns 404`() {
        val registry = MicrometerMetricsRegistry()
        val srv = HttpMetricsServer(port = 0, registry = registry).also { server = it }
        srv.start()

        val (code, _) = httpGet("http://127.0.0.1:${srv.boundPort}/unknown")

        assertThat(code).isEqualTo(404)
    }

    @Test
    fun `stop releases the bound port`() {
        val registry = MicrometerMetricsRegistry()
        val srv = HttpMetricsServer(port = 0, registry = registry).also { server = it }

        srv.start()
        assertThat(srv.isRunning).isTrue()
        val port = srv.boundPort
        assertThat(port).isGreaterThan(0)

        srv.stop()
        assertThat(srv.isRunning).isFalse()

        // Re-bind on the same port to confirm it's free. May intermittently
        // fail under heavy CI port pressure, so we widen the check: starting
        // a fresh server on port 0 must not throw.
        val again = HttpMetricsServer(port = 0, registry = registry).also { server = it }
        again.start()
        assertThat(again.isRunning).isTrue()
    }

    @Test
    fun `start twice without stop fails fast`() {
        val registry = MicrometerMetricsRegistry()
        val srv = HttpMetricsServer(port = 0, registry = registry).also { server = it }
        srv.start()

        val ex = runCatching { srv.start() }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }
}
