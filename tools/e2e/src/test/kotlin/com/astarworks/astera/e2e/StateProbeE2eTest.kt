package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Exercises the same probe protocol on both real server lanes, including cross-chunk counts. */
class StateProbeE2eTest : E2eBase() {
    @Test
    fun `owning scheduler probes return state and never turn errors into empty results`() {
        val a = bot("ProbeA", PackPolicy.DECLINE)
        val b = bot("ProbeB", PackPolicy.DECLINE)
        a.waitUntil(5_000) { a.positionPackets.get() > 0 && a.hotbar()[8] != -1 }
        b.waitUntil(5_000) { b.positionPackets.get() > 0 && b.hotbar()[8] != -1 }
        val y = a.y
        rcon("execute in minecraft:overworld run tp ${a.name} -0.5 $y 0.5 0 0")
        rcon("execute in minecraft:overworld run tp ${b.name} 0.5 $y 0.5 0 0")
        Thread.sleep(1_000)
        val suite = "State probes"
        E2eReport.check(suite, "name and UUID identify the same entity with position, dimension and health", "e2eprobe entity") {
            assertThat(entityData(a.name, "UUID")).isEqualTo(a.uuid.toString())
            assertThat(entityData(a.name, "Pos")).startsWith("[-0.5d,")
            assertThat(entityData(a.uuid.toString(), "Pos")).startsWith("[-0.5d,")
            assertThat(entityData(a.uuid.toString(), "Dimension")).isEqualTo("\"minecraft:overworld\"")
            assertThat(entityData(a.uuid.toString(), "Health").removeSuffix("f").toDouble()).isGreaterThan(0.0)
            "name=${a.name} uuid=${a.uuid} position=${entityData(a.name, "Pos")}"
        }
        E2eReport.check(suite, "block probes distinguish matching, nonmatching and nonair at negative coordinates", "e2eprobe block") {
            assertThat(probe("block world -17 319 0 minecraft:air")).isEqualTo("true")
            assertThat(probe("block world -17 319 0 minecraft:stone")).isEqualTo("false")
            assertThat(probe("block world -17 319 0 nonair")).isEqualTo("false")
            "air at (-17,319,0)"
        }
        E2eReport.check(suite, "entity counts cover both sides of a chunk boundary and preserve type/tag/radius filters", "e2eprobe count") {
            assertThat(probe("count world 0 $y 0.5 minecraft:player * 3").toInt()).isEqualTo(2)
            assertThat(nearbyCount(a.name, "minecraft:player", radius = "3")).isEqualTo(2)
            assertThat(nearbyCount(a.name, "minecraft:player", "e2e_nonexistent_tag", "3")).isZero()
            assertThat(nearbyCount(a.name, "minecraft:zombie", radius = "3")).isZero()
            "two players across chunks -1/0; no tagged players or zombies"
        }
        E2eReport.check(suite, "missing targets and bad fields fail explicitly", "e2eprobe ERROR") {
            assertThatThrownBy { entityData("E2eMissingTarget", "Health") }.hasMessageContaining("State probe failed")
            assertThatThrownBy { entityData(a.name, "unknown") }.hasMessageContaining("unknown field")
            "errors are not interpreted as zero, false, or missing health"
        }
    }
}
