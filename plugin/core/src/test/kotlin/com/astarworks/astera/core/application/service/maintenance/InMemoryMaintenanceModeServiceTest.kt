package com.astarworks.astera.core.application.service.maintenance

import com.astarworks.astera.core.domain.maintenance.MaintenanceMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryMaintenanceModeServiceTest {

    private val t0 = Instant.parse("2026-05-27T10:00:00Z")
    private val t1 = Instant.parse("2026-05-27T10:05:00Z")
    private val t2 = Instant.parse("2026-05-27T10:10:00Z")

    @Test
    fun `default state is Off at EPOCH`() {
        val svc = InMemoryMaintenanceModeService()
        val current = svc.current()
        assertThat(current).isInstanceOf(MaintenanceMode.Off::class.java)
        assertThat(current.since).isEqualTo(Instant.EPOCH)
    }

    @Test
    fun `setOn flips state to On with given reason and scheduledOffAt`() {
        val svc = InMemoryMaintenanceModeService()
        val result = svc.setOn(t0, reason = "DB migration", scheduledOffAt = t2)
        assertThat(result.since).isEqualTo(t0)
        assertThat(result.reason).isEqualTo("DB migration")
        assertThat(result.scheduledOffAt).isEqualTo(t2)
        assertThat(svc.current()).isEqualTo(result)
    }

    @Test
    fun `setOn while already On preserves original since`() {
        val svc = InMemoryMaintenanceModeService()
        svc.setOn(t0, reason = "first", scheduledOffAt = null)
        val updated = svc.setOn(t1, reason = "amended reason", scheduledOffAt = t2)
        assertThat(updated.since).isEqualTo(t0)  // unchanged
        assertThat(updated.reason).isEqualTo("amended reason")
        assertThat(updated.scheduledOffAt).isEqualTo(t2)
    }

    @Test
    fun `setOff returns Off with new since timestamp`() {
        val svc = InMemoryMaintenanceModeService()
        svc.setOn(t0, reason = null, scheduledOffAt = null)
        val off = svc.setOff(t1)
        assertThat(off.since).isEqualTo(t1)
        assertThat(svc.current()).isEqualTo(off)
    }

    @Test
    fun `setOff while already Off updates since`() {
        val svc = InMemoryMaintenanceModeService(MaintenanceMode.Off(t0))
        val off = svc.setOff(t1)
        assertThat(off.since).isEqualTo(t1)
    }
}
