package com.astarworks.astera.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResultTest {

    @Test
    fun `success carries value`() {
        val r: Result<Int, String> = Result.success(42)
        assertThat(r.isSuccess).isTrue()
        assertThat(r.isFailure).isFalse()
        assertThat(r.getOrNull()).isEqualTo(42)
        assertThat(r.errorOrNull()).isNull()
    }

    @Test
    fun `failure carries error`() {
        val r: Result<Int, String> = Result.failure("boom")
        assertThat(r.isSuccess).isFalse()
        assertThat(r.isFailure).isTrue()
        assertThat(r.getOrNull()).isNull()
        assertThat(r.errorOrNull()).isEqualTo("boom")
    }

    @Test
    fun `map transforms success, passes failure`() {
        val ok: Result<Int, String> = Result.success(5)
        assertThat(ok.map { it * 2 }.getOrNull()).isEqualTo(10)

        val err: Result<Int, String> = Result.failure("x")
        assertThat(err.map { it * 2 }.errorOrNull()).isEqualTo("x")
    }

    @Test
    fun `flatMap chains, short-circuits on failure`() {
        val ok: Result<Int, String> = Result.success(5)
        assertThat(ok.flatMap { Result.success(it + 1) }.getOrNull()).isEqualTo(6)

        // Annotate the source with the failure type so `flatMap` can infer
        // the downstream `Result<Int, String>` shape.
        val src: Result<Int, String> = Result.success(5)
        val downstreamFails: Result<Int, String> = src.flatMap { Result.failure("downstream") }
        assertThat(downstreamFails.errorOrNull()).isEqualTo("downstream")

        val upstream: Result<Int, String> = Result.failure("upstream")
        val upstreamFails: Result<Int, String> = upstream.flatMap { Result.success(99) }
        assertThat(upstreamFails.errorOrNull()).isEqualTo("upstream")
    }

    @Test
    fun `mapError transforms failure, passes success`() {
        val r: Result<Int, String> = Result.failure("e")
        assertThat(r.mapError { it.uppercase() }.errorOrNull()).isEqualTo("E")

        val ok: Result<Int, String> = Result.success(1)
        assertThat(ok.mapError { "should not run" }.getOrNull()).isEqualTo(1)
    }

    @Test
    fun `fold collapses to single value`() {
        val ok: Result<Int, String> = Result.success(7)
        assertThat(ok.fold(onSuccess = { it * 10 }, onFailure = { -1 })).isEqualTo(70)

        val err: Result<Int, String> = Result.failure("x")
        assertThat(err.fold(onSuccess = { it * 10 }, onFailure = { -1 })).isEqualTo(-1)
    }

    @Test
    fun `onSuccess and onFailure fire selectively`() {
        var ok = 0
        var bad = 0
        Result.success<Int>(1).onSuccess { ok++ }.onFailure { bad++ }
        Result.failure<String>("e").onSuccess { ok++ }.onFailure { bad++ }
        assertThat(ok).isEqualTo(1)
        assertThat(bad).isEqualTo(1)
    }
}
