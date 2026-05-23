package com.astarworks.astera.core.domain.engagement

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EngagementValueObjectsTest {

    @Test
    fun `EngagementScore accepts boundary values`() {
        assertThat(EngagementScore(0.0).value).isEqualTo(0.0)
        assertThat(EngagementScore(1.0).value).isEqualTo(1.0)
        assertThat(EngagementScore.ZERO.value).isEqualTo(0.0)
        assertThat(EngagementScore.MAX.value).isEqualTo(1.0)
    }

    @Test
    fun `EngagementScore rejects out-of-range and NaN`() {
        assertThatThrownBy { EngagementScore(-0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EngagementScore(1.01) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EngagementScore(Double.NaN) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RetentionRate enforces 0 to 1 range`() {
        assertThat(RetentionRate(0.5).value).isEqualTo(0.5)
        assertThatThrownBy { RetentionRate(-0.01) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RetentionRate(1.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RetentionRate(Double.NaN) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RetentionDay requires non-negative day`() {
        assertThat(RetentionDay.D1.day).isEqualTo(1)
        assertThat(RetentionDay.D7.day).isEqualTo(7)
        assertThat(RetentionDay.D30.day).isEqualTo(30)
        assertThat(RetentionDay.STANDARD_BINS).containsExactly(
            RetentionDay.D1, RetentionDay.D7, RetentionDay.D30,
        )
        assertThatThrownBy { RetentionDay(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RetentionCohort id renders ISO-8601 date`() {
        assertThat(RetentionCohort(LocalDate.of(2026, 5, 20)).id).isEqualTo("2026-05-20")
    }

    @Test
    fun `DauWauMau enforces the inclusion invariant`() {
        val ok = DauWauMau(dau = 5, wau = 10, mau = 25)
        assertThat(ok.dau).isEqualTo(5)
        assertThat(ok.stickiness).isEqualTo(0.2)

        assertThatThrownBy { DauWauMau(dau = 11, wau = 10, mau = 25) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DauWauMau(dau = 5, wau = 100, mau = 50) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DauWauMau(dau = -1, wau = 10, mau = 25) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `DauWauMau stickiness returns 0 when MAU is 0`() {
        assertThat(DauWauMau.EMPTY.stickiness).isEqualTo(0.0)
    }
}
