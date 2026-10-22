package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.norma.PeriodKind
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.contents.NormaContents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** ADR-0210: daily / weekly missions restart in a new window instead of staying completed forever. */
class InMemoryNormaServicePeriodTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val player = PlayerId(UUID.randomUUID())
    private val clock = MutableClock(Instant.parse("2026-10-21T10:00:00Z")) // Wednesday

    private fun norma(id: String, period: PeriodKind) = Norma(
        NormaId(id), NormaTarget.Kills(2), NormaReward("STAR", 10, 5), period, MessageKey("astera.norma.$id.name"),
    )

    private val service = InMemoryNormaService(
        InMemoryNormaProgressRepository(),
        NormaContents().also {
            it.register(norma("daily", PeriodKind.Daily))
            it.register(norma("weekly", PeriodKind.Weekly))
            it.register(norma("season", PeriodKind.Seasonal))
        },
        clock,
    )

    private fun view(id: String) = service.views(player).first { it.norma.id == NormaId(id) }

    @Test
    fun `a completed daily mission is available again the next day`() {
        service.recordProgress(player, NormaTarget.Kills(1), 2)
        assertThat(view("daily").completed).isTrue

        clock.now = Instant.parse("2026-10-22T00:05:00Z")

        assertThat(view("daily").completed).isFalse
        assertThat(view("daily").current).isZero
        val done = service.recordProgress(player, NormaTarget.Kills(1), 2)
        assertThat(done.map { it.normaId }).contains(NormaId("daily"))
    }

    @Test
    fun `partial daily progress does not carry into the next day`() {
        service.recordProgress(player, NormaTarget.Kills(1), 1)
        clock.now = Instant.parse("2026-10-22T09:00:00Z")

        service.recordProgress(player, NormaTarget.Kills(1), 1)

        assertThat(view("daily").current).isEqualTo(1L)
        assertThat(view("daily").completed).isFalse
    }

    @Test
    fun `a weekly mission survives the weekday change but resets on Monday`() {
        service.recordProgress(player, NormaTarget.Kills(1), 2)

        clock.now = Instant.parse("2026-10-25T20:00:00Z") // Sunday, same ISO week
        assertThat(view("weekly").completed).isTrue

        clock.now = Instant.parse("2026-10-26T00:10:00Z") // Monday
        assertThat(view("weekly").completed).isFalse
    }

    @Test
    fun `a seasonal mission never rolls over`() {
        service.recordProgress(player, NormaTarget.Kills(1), 2)

        clock.now = Instant.parse("2027-03-01T00:00:00Z")

        assertThat(view("season").completed).isTrue
    }
}
