package com.astarworks.astera.core.domain.geometry.spatial

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.Region
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AabbTreeTest {

    private fun aabb(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): Region =
        Region(Vec3(x0, y0, z0), Vec3(x1, y1, z1))

    @Test
    fun `empty tree returns empty queries`() {
        val tree = AabbTree<String>()
        assertThat(tree.size).isZero()
        assertThat(tree.queryPoint(Vec3(0.0, 0.0, 0.0))).isEmpty()
        assertThat(tree.queryRange(Vec3(0.0, 0.0, 0.0), 100.0)).isEmpty()
    }

    @Test
    fun `insert + queryPoint finds containing region`() {
        val tree = AabbTree<String>()
        tree.insert("box", aabb(0.0, 0.0, 0.0, 10.0, 10.0, 10.0))

        assertThat(tree.queryPoint(Vec3(5.0, 5.0, 5.0))).containsExactly("box")
        assertThat(tree.queryPoint(Vec3(50.0, 5.0, 5.0))).isEmpty()
    }

    @Test
    fun `queryPoint at boundary is inclusive`() {
        val tree = AabbTree<String>()
        tree.insert("box", aabb(0.0, 0.0, 0.0, 10.0, 10.0, 10.0))

        assertThat(tree.queryPoint(Vec3(0.0, 0.0, 0.0))).containsExactly("box")
        assertThat(tree.queryPoint(Vec3(10.0, 10.0, 10.0))).containsExactly("box")
    }

    @Test
    fun `multiple overlapping regions return all matches`() {
        val tree = AabbTree<String>()
        tree.insert("a", aabb(0.0, 0.0, 0.0, 10.0, 10.0, 10.0))
        tree.insert("b", aabb(5.0, 5.0, 5.0, 15.0, 15.0, 15.0))

        assertThat(tree.queryPoint(Vec3(7.0, 7.0, 7.0))).containsExactlyInAnyOrder("a", "b")
        assertThat(tree.queryPoint(Vec3(2.0, 2.0, 2.0))).containsExactly("a")
        assertThat(tree.queryPoint(Vec3(12.0, 12.0, 12.0))).containsExactly("b")
    }

    @Test
    fun `queryRange returns regions whose AABB intersects the sphere`() {
        val tree = AabbTree<String>()
        tree.insert("near", aabb(0.0, 0.0, 0.0, 2.0, 2.0, 2.0))
        tree.insert("far", aabb(100.0, 100.0, 100.0, 102.0, 102.0, 102.0))

        val nearCenter = Vec3(1.0, 1.0, 1.0)
        assertThat(tree.queryRange(nearCenter, 5.0)).containsExactly("near")
        assertThat(tree.queryRange(nearCenter, 0.0)).containsExactly("near")
        assertThat(tree.queryRange(Vec3(50.0, 50.0, 50.0), 10.0)).isEmpty()
    }

    @Test
    fun `queryRange with negative radius throws`() {
        val tree = AabbTree<String>()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            tree.queryRange(Vec3.ZERO, -1.0)
        }
    }

    @Test
    fun `remove drops value and returns true`() {
        val tree = AabbTree<String>()
        tree.insert("box", aabb(0.0, 0.0, 0.0, 10.0, 10.0, 10.0))
        assertThat(tree.remove("box")).isTrue()
        assertThat(tree.size).isZero()
        assertThat(tree.queryPoint(Vec3(5.0, 5.0, 5.0))).isEmpty()
    }

    @Test
    fun `remove unknown value returns false`() {
        val tree = AabbTree<String>()
        assertThat(tree.remove("unknown")).isFalse()
    }

    @Test
    fun `insert with existing value replaces AABB`() {
        val tree = AabbTree<String>()
        tree.insert("box", aabb(0.0, 0.0, 0.0, 10.0, 10.0, 10.0))
        tree.insert("box", aabb(100.0, 0.0, 0.0, 110.0, 10.0, 10.0))

        assertThat(tree.size).isEqualTo(1)
        assertThat(tree.queryPoint(Vec3(5.0, 5.0, 5.0))).isEmpty()
        assertThat(tree.queryPoint(Vec3(105.0, 5.0, 5.0))).containsExactly("box")
    }

    @Test
    fun `regionOf returns the AABB or null`() {
        val tree = AabbTree<String>()
        val r = aabb(0.0, 0.0, 0.0, 10.0, 10.0, 10.0)
        tree.insert("box", r)

        assertThat(tree.regionOf("box")).isEqualTo(r)
        assertThat(tree.regionOf("unknown")).isNull()
    }

    @Test
    fun `clear empties the tree`() {
        val tree = AabbTree<String>()
        tree.insert("a", aabb(0.0, 0.0, 0.0, 10.0, 10.0, 10.0))
        tree.insert("b", aabb(20.0, 0.0, 0.0, 30.0, 10.0, 10.0))

        tree.clear()
        assertThat(tree.size).isZero()
        assertThat(tree.queryPoint(Vec3(5.0, 5.0, 5.0))).isEmpty()
    }
}
