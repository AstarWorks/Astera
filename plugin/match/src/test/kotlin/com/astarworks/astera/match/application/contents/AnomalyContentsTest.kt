package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.config.AnomalyScheduleYamlConfig
import com.astarworks.astera.match.application.config.AnomalyYamlConfig
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * New in Wave _infra-2b (ADR-0065). Anomaly schedules are authored as
 * top-level `AnomalyScheduleYamlConfig` envelopes under `content/anomaly/`
 * (one file per stage). [AnomalyContents] subclasses
 * [com.astarworks.astera.core.application.contents.YamlDynamicContents] so
 * the standard bootstrap / reload / programmatic-register surface is
 * available.
 *
 * The 3 reload-semantics tests (atomic swap, abort-on-failure, programmatic
 * register) mirror the `WeaponContentsTest` shape (ADR-0060).
 */
class AnomalyContentsTest {

    private val validYaml = """
        stage_id: test-arena
        anomalies:
          - type: resource_spike
            weight: 5
            duration_seconds: 30.0
            attribute_key: generator.period_multiplier
            operation: MULTIPLY
            value: 0.5
          - type: status_burst
            weight: 3
            duration_seconds: 15.0
            attribute_key: status.burn_intensity
            operation: ADD
            value: 1.0
    """.trimIndent()

    private val secondValidYaml = """
        stage_id: another-arena
        anomalies:
          - type: time_warp
            weight: 2
            duration_seconds: 10.0
            attribute_key: match.tempo_multiplier
            operation: MULTIPLY
            value: 2.0
    """.trimIndent()

    @Test
    fun `bootstrap loads one valid schedule`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)

        val contents = AnomalyContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val descriptors = contents.find(StageId("test-arena"))
        assertThat(descriptors).isNotNull
        requireNotNull(descriptors)
        assertThat(descriptors).hasSize(2)
        assertThat(descriptors[0].type).isEqualTo(AnomalyType.ResourceSpike)
        assertThat(descriptors[0].modifierOperation).isEqualTo(AttributeModifier.Operation.MULTIPLY)
        assertThat(descriptors[0].durationTicks).isEqualTo(600L) // 30s * 20 TPS
        assertThat(descriptors[1].type).isEqualTo(AnomalyType.StatusBurst)
    }

    @Test
    fun `bootstrap is best-effort - malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("broken.yaml"), "stage_id: : : not valid : yaml :::\n  - oops")

        val contents = AnomalyContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(StageId("test-arena"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `bootstrap fails the file when any descriptor in the list is invalid`(@TempDir dir: Path) {
        // weight=0 violates AnomalyYamlConfig.toDescriptor's `weight >= 1`
        // check, so the entire file (= one schedule) is counted as failed.
        val badYaml = """
            stage_id: bad-arena
            anomalies:
              - type: resource_spike
                weight: 0
                duration_seconds: 30.0
                attribute_key: generator.period_multiplier
                operation: MULTIPLY
                value: 0.5
        """.trimIndent()
        Files.writeString(dir.resolve("bad.yaml"), badYaml)

        val contents = AnomalyContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(StageId("bad-arena"))).isNull()
    }

    @Test
    fun `missing directory returns zero counts and registry stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = AnomalyContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)

        val contents = AnomalyContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the schedule with one for a different stage.
        Files.delete(dir.resolve("test-arena.yaml"))
        Files.writeString(dir.resolve("another-arena.yaml"), secondValidYaml)

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(StageId("test-arena"))).isNull()
        assertThat(contents.find(StageId("another-arena"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)

        val contents = AnomalyContents()
        contents.bootstrap(dir)
        assertThat(contents.find(StageId("test-arena"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "stage_id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(StageId("test-arena"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = AnomalyContents()
        val schedule = AnomalyScheduleYamlConfig(
            stageId = "programmatic",
            anomalies = listOf(
                AnomalyYamlConfig(
                    type = "resource_spike",
                    weight = 5,
                    durationSeconds = 30.0,
                    attributeKey = "generator.period_multiplier",
                    operation = "MULTIPLY",
                    value = 0.5,
                ),
            ),
        )

        contents.register(schedule)

        val descriptors = contents.find(StageId("programmatic"))
        assertThat(descriptors).isNotNull
        requireNotNull(descriptors)
        assertThat(descriptors).hasSize(1)
        assertThat(descriptors[0].type).isEqualTo(AnomalyType.ResourceSpike)
    }
}
