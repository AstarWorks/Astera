package com.astarworks.astera.core.adapter.paper.hud

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pure invariants for [phantomTabListUuid] (Wave 6c-stub-packetevents-D,
 * ADR-0173). Correctness of the per-viewer synthetic-row lifecycle rests
 * on these: a stable UUID per (viewer, name) makes ADD idempotent and
 * REMOVE precise, while per-viewer scoping keeps one viewer's REMOVE from
 * tearing down another viewer's row. No Paper harness needed.
 */
class TabListPhantomUuidTest {

    private val viewerA = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val viewerB = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    @Test
    fun `same viewer and name yields a stable uuid across calls`() {
        assertThat(phantomTabListUuid(viewerA, "NPC-Sentry"))
            .isEqualTo(phantomTabListUuid(viewerA, "NPC-Sentry"))
    }

    @Test
    fun `different viewers get different phantom uuids for the same name`() {
        assertThat(phantomTabListUuid(viewerA, "NPC-Sentry"))
            .isNotEqualTo(phantomTabListUuid(viewerB, "NPC-Sentry"))
    }

    @Test
    fun `different names get different phantom uuids for the same viewer`() {
        assertThat(phantomTabListUuid(viewerA, "NPC-Sentry"))
            .isNotEqualTo(phantomTabListUuid(viewerA, "NPC-Healer"))
    }

    @Test
    fun `derivation matches the documented name-based scheme`() {
        val expected = UUID.nameUUIDFromBytes("$viewerA:NPC-Sentry".toByteArray())
        assertThat(phantomTabListUuid(viewerA, "NPC-Sentry")).isEqualTo(expected)
    }
}
