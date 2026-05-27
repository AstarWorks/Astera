package com.astarworks.astera.core.domain.hologram.transition

import com.astarworks.astera.core.domain.animation.Easing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HologramTimelineTest {

    @Test
    fun `timeline rejects empty transition list`() {
        assertThatThrownBy {
            HologramTimeline(emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `timeline default loopMode is PlayOnce`() {
        val timeline = HologramTimeline(
            transitions = listOf(HologramTransition.ShadowRadius(1f, 5, Easing.LINEAR)),
        )
        assertThat(timeline.loopMode).isEqualTo(LoopMode.PlayOnce)
    }

    @Test
    fun `LoopMode Loop accepts null times for infinite`() {
        val infinite = LoopMode.Loop(null)
        assertThat(infinite.times).isNull()
    }

    @Test
    fun `LoopMode Loop accepts positive times`() {
        val finite = LoopMode.Loop(5)
        assertThat(finite.times).isEqualTo(5)
    }

    @Test
    fun `LoopMode Loop rejects zero or negative times`() {
        assertThatThrownBy {
            LoopMode.Loop(0)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            LoopMode.Loop(-1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `LoopMode PingPong accepts null times for infinite`() {
        val infinite = LoopMode.PingPong(null)
        assertThat(infinite.times).isNull()
    }

    @Test
    fun `LoopMode PingPong rejects zero or negative times`() {
        assertThatThrownBy {
            LoopMode.PingPong(0)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            LoopMode.PingPong(-3)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `LoopMode three variants exhaustively dispatchable`() {
        val modes: List<LoopMode> = listOf(
            LoopMode.PlayOnce,
            LoopMode.Loop(3),
            LoopMode.PingPong(2),
        )
        val tagged = modes.map { mode ->
            when (mode) {
                LoopMode.PlayOnce -> "play_once"
                is LoopMode.Loop -> "loop"
                is LoopMode.PingPong -> "ping_pong"
            }
        }
        assertThat(tagged).containsExactly("play_once", "loop", "ping_pong")
    }
}
