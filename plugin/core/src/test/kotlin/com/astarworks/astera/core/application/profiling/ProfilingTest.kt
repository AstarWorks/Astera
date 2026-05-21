package com.astarworks.astera.core.application.profiling

import com.astarworks.astera.core.application.port.outbound.profiling.ProfilingSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class InMemoryProfilingCollectorTest {

    @Test
    fun `records aggregate samples across categories`() {
        val c = InMemoryProfilingCollector()
        c.record("combat.damage", "ApplyDamageUseCase.execute", 1000)
        c.record("combat.damage", "ApplyDamageUseCase.execute", 2000)
        c.record("match.lifecycle", "StartMatchUseCase.execute", 5000)

        val snapshot = c.export()
        assertThat(snapshot.samples).hasSize(2)
        val combat = snapshot.samples.single { it.methodName == "ApplyDamageUseCase.execute" }
        assertThat(combat.totalNanos).isEqualTo(3000)
        assertThat(combat.invocationCount).isEqualTo(2)
    }

    @Test
    fun `flamegraph format renders descending by total nanos`() {
        val c = InMemoryProfilingCollector()
        c.record("light", "fast", 100)
        c.record("heavy", "slow", 10_000)

        val txt = c.export().toFlamegraphFormat()
        val lines = txt.lines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo("heavy;slow 10000")
        assertThat(lines[1]).isEqualTo("light;fast 100")
    }

    @Test
    fun `empty snapshot renders to empty string`() {
        assertThat(ProfilingSnapshot.empty.toFlamegraphFormat()).isEmpty()
    }

    @Test
    fun `reset clears all samples`() {
        val c = InMemoryProfilingCollector()
        c.record("a", "m", 1)
        c.reset()
        assertThat(c.export().samples).isEmpty()
    }
}

class ProfiledRunnerTest {

    @Test
    fun `run records duration with the supplied category and method`() {
        val c = InMemoryProfilingCollector()
        val result = ProfiledRunner.run(c, "combat.damage", "ApplyDamage.execute") {
            Thread.sleep(5)
            42
        }
        assertThat(result).isEqualTo(42)
        val sample = c.export().samples.single()
        assertThat(sample.category).isEqualTo("combat.damage")
        assertThat(sample.methodName).isEqualTo("ApplyDamage.execute")
        assertThat(sample.totalNanos).isGreaterThan(0L)
    }

    @Test
    fun `run still records on exception`() {
        val c = InMemoryProfilingCollector()
        runCatching {
            ProfiledRunner.run(c, "cat", "boom") {
                throw IllegalStateException("oops")
            }
        }
        assertThat(c.export().samples.single().invocationCount).isEqualTo(1)
    }
}

class ProfilingExportServiceTest {

    @Test
    fun `export writes flamegraph file with deterministic name`(@TempDir dir: Path) {
        val c = InMemoryProfilingCollector()
        c.record("cat", "m", 1234)
        val fixedTime = Instant.parse("2026-05-20T01:02:03Z")
        val svc = ProfilingExportService(c, dir, nowSupplier = { fixedTime })

        val file = svc.export(reset = false)
        assertThat(file.fileName.toString()).isEqualTo("profiling-20260520-010203.txt")
        assertThat(Files.readString(file)).isEqualTo("cat;m 1234")
    }

    @Test
    fun `export with reset clears samples`(@TempDir dir: Path) {
        val c = InMemoryProfilingCollector()
        c.record("cat", "m", 100)
        val svc = ProfilingExportService(c, dir)
        svc.export(reset = true)
        assertThat(c.export().samples).isEmpty()
    }
}
