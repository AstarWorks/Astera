package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Wave 5a — `FriendlyFireBlocked` is a plain data event. Verifies the
 * record carries attacker, intendedTarget, source unchanged, and that
 * equality / copy behave as expected for a data class.
 */
class FriendlyFireBlockedTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())
    private val source = DamageSource.Environment

    @Test
    fun `data class retains attacker intendedTarget and source`() {
        val event = FriendlyFireBlocked(
            attacker = attacker,
            intendedTarget = target,
            source = source,
        )

        assertThat(event.attacker).isEqualTo(attacker)
        assertThat(event.intendedTarget).isEqualTo(target)
        assertThat(event.source).isSameAs(source)
        assertThat(event.version).isEqualTo(1)
        assertThat(event.occurredAt).isBeforeOrEqualTo(Instant.now())
    }

    @Test
    fun `events with same attributes (and fixed timestamp) are equal`() {
        val fixed = Instant.parse("2025-01-01T00:00:00Z")
        val a = FriendlyFireBlocked(attacker, target, source, occurredAt = fixed)
        val b = FriendlyFireBlocked(attacker, target, source, occurredAt = fixed)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `copy preserves all fields by default`() {
        val original = FriendlyFireBlocked(attacker, target, source)
        val copy = original.copy()
        assertThat(copy).isEqualTo(original)
    }
}
