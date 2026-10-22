package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ConeSelectorTest {

    private val eye = Vec3(0.0, 1.62, 0.0)
    private val south = Vec3(0.0, 0.0, 1.0)

    private fun target(x: Double, y: Double, z: Double, height: Double = 1.8) =
        NearbyTarget(PlayerId(UUID.randomUUID()), Vec3(x, y, z), height)

    private fun select(range: Double, angle: Double, vararg ts: NearbyTarget) =
        ConeSelector.select(eye, south, range, angle, ts.toList())

    @Test
    fun `target straight ahead inside range is selected`() {
        val t = target(0.0, 0.0, 2.0)
        assertThat(select(3.0, 90.0, t)).containsExactly(t)
    }

    @Test
    fun `target beyond range is rejected`() {
        assertThat(select(3.0, 90.0, target(0.0, 0.0, 5.0))).isEmpty()
    }

    @Test
    fun `target behind the caster is rejected`() {
        assertThat(select(3.0, 120.0, target(0.0, 0.0, -1.5))).isEmpty()
    }

    @Test
    fun `half angle boundary - 40 degrees off axis is in a 100 degree cone, 60 is out`() {
        val inside = target(2.0 * Math.sin(Math.toRadians(40.0)), 0.0, 2.0 * Math.cos(Math.toRadians(40.0)))
        val outside = target(2.0 * Math.sin(Math.toRadians(60.0)), 0.0, 2.0 * Math.cos(Math.toRadians(60.0)))
        assertThat(select(3.0, 100.0, inside, outside)).containsExactly(inside)
    }

    @Test
    fun `360 degree arc hits all around`() {
        val front = target(0.0, 0.0, 2.0)
        val back = target(0.0, 0.0, -2.0)
        assertThat(select(3.0, 360.0, front, back)).containsExactlyInAnyOrder(front, back)
    }

    @Test
    fun `result is ordered nearest first`() {
        val far = target(0.0, 0.0, 2.5)
        val near = target(0.0, 0.0, 1.0)
        assertThat(select(3.0, 90.0, far, near)).containsExactly(near, far)
    }

    @Test
    fun `body centre is measured, so a tall mob with feet below the eye line still counts`() {
        val tall = target(0.0, 0.0, 1.0, height = 3.0)
        assertThat(select(3.0, 60.0, tall)).containsExactly(tall)
    }

    @Test
    fun `target without position uses the fallback or is skipped`() {
        val unknown = NearbyTarget(PlayerId(UUID.randomUUID()), position = null)
        assertThat(ConeSelector.select(eye, south, 3.0, 90.0, listOf(unknown))).isEmpty()
        val withFallback = ConeSelector.select(eye, south, 3.0, 90.0, listOf(unknown)) { Vec3(0.0, 0.0, 2.0) }
        assertThat(withFallback).containsExactly(unknown)
    }

    @Test
    fun `zero direction selects nothing`() {
        val t = target(0.0, 0.0, 1.0)
        assertThat(ConeSelector.select(eye, Vec3.ZERO, 3.0, 90.0, listOf(t))).isEmpty()
    }
}
