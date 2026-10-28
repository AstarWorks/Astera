package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.application.skillengine.load.worldDecoders
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.SkillWorldEditor
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.worldExecutors
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameError
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.WorldOp
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.skillengine.world.WorldAim
import com.astarworks.astera.core.domain.skillengine.world.WorldShapes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** SKL-08 W14: world ops produce the expected cell sets, revert on time, honour the per-skill break flag. */
class WorldOpsScenarioTest {

    private class FakeWorld : ISkillWorldGateway {
        val blocks = HashMap<BlockCell, String>()
        val hp = HashMap<BlockCell, Int>()
        val cover = HashSet<BlockCell>()
        val failures = ArrayList<String>()
        var items = 100
        override fun blockAt(cell: BlockCell) = blocks[cell]
        override fun canEdit(cast: CastId, cell: BlockCell, outsideArena: Boolean) = outsideArena || cell.y >= 0
        override fun isCover(cell: BlockCell) = cell in cover
        override fun isCoverBlock(blockKey: String) = blockKey.startsWith("astera:")
        override fun place(cell: BlockCell, blockKey: String): Boolean {
            if (blockKey == UNPLACEABLE) return false
            blocks[cell] = blockKey
            return true
        }
        override fun remove(cell: BlockCell) { blocks.remove(cell) }
        override fun placeCover(cast: CastId, cell: BlockCell, blockKey: String): Boolean { blocks[cell] = blockKey; cover += cell; return true }
        override fun damage(cast: CastId, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
            val left = (hp[cell] ?: 100) - blockDamage
            hp[cell] = left
            if (left <= 0) { blocks.remove(cell); return BlockDamageOutcome.BROKEN }
            return if (blockDamage > 0) BlockDamageOutcome.CHIPPED else BlockDamageOutcome.NONE
        }
        override fun consumeItem(cast: CastId, itemId: String): Boolean = items > 0.also { if (items > 0) items-- }
    }

    /** Caster at the feet cell (0,64,0) looking +X; only worldOf/pointWorld are used by world ops. */
    private class FakeTree : FrameTree {
        private val caster = Frame.fromDirection(Vec3(0.5, 64.0, 0.5), Vec3(1.0, 0.0, 0.0))
        override fun node(id: FrameId): FrameNode? = null
        override fun children(id: FrameId): List<FrameId> = emptyList()
        override fun add(node: FrameNode): Result<Unit, FrameError> = Result.Success(Unit)
        override fun remove(id: FrameId) = Unit
        override fun setLocal(id: FrameId, local: Frame): Result<Unit, FrameError> = Result.Success(Unit)
        override fun worldOf(id: FrameId): Result<Frame, FrameError> = Result.Success(caster)
        override fun reparent(id: FrameId, newParent: FrameId, keepWorld: Boolean, snap: Frame?): Result<Unit, FrameError> = Result.Success(Unit)
        override fun definePoint(point: FramePoint) = Unit
        override fun point(node: FrameId, name: String): FramePoint? = null
        override fun pointWorld(node: FrameId, name: String?): Result<Vec3, FrameError> = Result.Success(caster.origin)
    }

    private object Literals : ValueResolver {
        override fun num(value: Value<Double>) = (value as Value.Literal).value
        override fun vec3(value: Value<Vec3>) = (value as Value.Literal).value
        override fun quat(value: Value<Quaternion>) = (value as Value.Literal).value
        override fun bool(value: Value<Boolean>) = (value as Value.Literal).value
        override fun text(value: Value<String>) = (value as Value.Literal).value
    }

    private class Fixture {
        val world = FakeWorld()
        val editor = SkillWorldEditor(world)
        private val tree = FakeTree()
        private val executors = worldExecutors(editor)
        private val ctx = object : OpContext {
        override val host: com.astarworks.astera.core.application.skillengine.runtime.OpHost = java.lang.reflect.Proxy.newProxyInstance(
                com.astarworks.astera.core.application.skillengine.runtime.OpHost::class.java.classLoader,
                arrayOf(com.astarworks.astera.core.application.skillengine.runtime.OpHost::class.java),
            ) { _, m, args ->
                if (m.name == "fail") world.failures += "${args[0]}: ${args[1]}" else error("world ops reach only OpHost.fail, not ${m.name}")
                null
            } as com.astarworks.astera.core.application.skillengine.runtime.OpHost
            override val cast = CastId(1)
            override val tree: FrameTree = this@Fixture.tree
            override val values: ValueResolver = Literals
            override val ports: SkillRuntimePorts get() = error("world ops use no SkillRuntimePorts")
            override fun raise(event: String) = Unit
            override fun run(ops: List<SkillOp>) = Unit
        }

        @Suppress("UNCHECKED_CAST")
        fun run(op: WorldOp) {
            val ex = executors.first { it.opClass.isInstance(op) } as OpExecutor<WorldOp>
            ex.execute(op, ctx)
        }

        fun advance(ticks: Int) = repeat(ticks) { editor.tick() }
        fun filled(prefix: String) = world.blocks.filterValues { it == prefix }.keys
    }

    private companion object {
        /** A key the gateway cannot place (Material.matchMaterial returns null in Paper). */
        const val UNPLACEABLE = "minecraft:not_a_block"
    }

    private val atCaster = LocationSpec.AtPoint(PointRef("caster"))

    private fun wall(revert: Double? = 100.0, outside: Boolean = false) = WorldOp.PlaceShape(
        "w", null, atCaster, WorldOp.BuildShape.WALL, "oak_planks", width = 3, height = 2, length = 3,
        consumeItem = null, revertAfterTicks = revert?.let { Value.Literal(it) }, outsideArena = outside,
    )

    private fun sphere(radius: Double, breaks: Boolean, damage: Int = 100, length: Double = 2.0) = WorldOp.BreakArea(
        "b", null, atCaster, WorldOp.AreaShape.SPHERE, radius, length, 1.0, 2.0, "PICKAXE", damage, breaks,
        Value.Literal(100.0), false,
    )

    @Test
    fun `wall shape is a 3 by 2 slab two blocks ahead and reverts on its timer`() {
        val f = Fixture()
        f.run(wall())
        f.advance(40)
        val cells = f.filled("minecraft:oak_planks")
        assertThat(cells).containsExactlyInAnyOrder(
            BlockCell(2, 64, -1), BlockCell(2, 64, 0), BlockCell(2, 64, 1),
            BlockCell(2, 65, -1), BlockCell(2, 65, 0), BlockCell(2, 65, 1),
        )
        // placed at tick 1..30, each reverts 100 ticks after its own placement
        f.advance(60)
        assertThat(f.world.blocks).isNotEmpty()
        f.advance(80)
        assertThat(f.world.blocks).isEmpty()
        assertThat(f.editor.pendingReverts).isZero()
    }

    @Test
    fun `break flag false is inert and true removes the sphere then restores it`() {
        val f = Fixture()
        // sphere centre = caster + 2 ahead = cell (2,64,0)
        val centre = BlockCell(2, 64, 0)
        val ball = WorldShapes.breakCells(sphere(1.0, true), WorldAim(Vec3(0.5, 64.5, 0.5), Vec3(1.0, 0.0, 0.0)))
        assertThat(ball).hasSize(7).contains(centre)
        ball.forEach { f.world.blocks[it] = "minecraft:stone" }
        f.world.blocks[BlockCell(0, 64, 0)] = "minecraft:bedrock"

        f.run(sphere(1.0, breaks = false))
        f.advance(40)
        assertThat(f.filled("minecraft:stone")).hasSize(7)

        f.run(sphere(1.0, breaks = true))
        f.advance(40)
        assertThat(f.filled("minecraft:stone")).isEmpty()
        assertThat(f.world.blocks[BlockCell(0, 64, 0)]).isEqualTo("minecraft:bedrock")
        f.advance(140)
        assertThat(f.filled("minecraft:stone")).hasSize(7)
    }

    @Test
    fun `weapon chip owes no revert and only breaks once hp is gone`() {
        val f = Fixture()
        val cell = BlockCell(2, 64, 0)
        f.world.blocks[cell] = "minecraft:stone"
        f.run(sphere(0.4, breaks = true, damage = 30))
        f.advance(40)
        assertThat(f.world.blocks[cell]).isEqualTo("minecraft:stone")
        assertThat(f.world.hp[cell]).isEqualTo(70)
        assertThat(f.editor.pendingReverts).isZero()
    }

    @Test
    fun `overlapping edits keep the first original and revertAll restores at once`() {
        val f = Fixture()
        val cell = BlockCell(2, 64, 0)
        f.world.blocks[cell] = "minecraft:stone"
        f.run(WorldOp.RemoveBlock("r", null, LocationSpec.AtValue(Value.Literal(Vec3(2.5, 64.5, 0.5))), true, Value.Literal(50.0), false))
        assertThat(f.world.blocks).doesNotContainKey(cell)
        f.run(WorldOp.SetBlock("s", null, LocationSpec.AtValue(Value.Literal(Vec3(2.5, 64.5, 0.5))), "dirt", Value.Literal(500.0), false))
        assertThat(f.world.blocks[cell]).isEqualTo("minecraft:dirt")
        f.advance(60)
        assertThat(f.world.blocks[cell]).isEqualTo("minecraft:dirt")
        f.editor.revertAll()
        assertThat(f.world.blocks[cell]).isEqualTo("minecraft:stone")
    }

    @Test
    fun `a block key the gateway cannot place is reported as an op failure and owes no revert`() {
        val f = Fixture()
        f.run(WorldOp.SetBlock("s", null, LocationSpec.AtValue(Value.Literal(Vec3(2.5, 64.5, 0.5))), UNPLACEABLE, Value.Literal(50.0), false))
        assertThat(f.world.failures).hasSize(1)
        assertThat(f.world.failures.single()).contains("s:").contains(UNPLACEABLE)
        assertThat(f.world.blocks).isEmpty()
        assertThat(f.editor.pendingReverts).isZero()

        f.run(WorldOp.PlaceShape("w", null, atCaster, WorldOp.BuildShape.WALL, UNPLACEABLE, 3, 2, 3, null, Value.Literal(50.0), false))
        f.advance(40)
        // One failure per cell the wall would have filled, none of them silently treated as success.
        assertThat(f.world.failures.drop(1)).hasSize(6).allMatch { it.startsWith("w:") }
        assertThat(f.world.blocks).isEmpty()
        assertThat(f.editor.pendingReverts).isZero()
    }

    @Test
    fun `decoder reads the yaml keys`() {
        val keys = mapOf<String, Any>(
            "shape" to "box", "length" to 3.0, "width" to 3.0, "height" to 2.0,
            "tool_class" to "shovel", "block_damage" to 40, "breaks" to false,
        )
        val reader = object : OpReader {
        override fun textValue(key: String): Value<String>? = null
        override fun entries(key: String): List<OpReader> = emptyList()
            override val path = "gameplay.on.release[dig]"
            override fun string(key: String) = keys[key] as? String
            override fun requireString(key: String) = string(key) ?: fail("missing $key")
            override fun int(key: String) = keys[key] as? Int
            override fun bool(key: String) = keys[key] as? Boolean
            override fun number(key: String) = (keys[key] as? Double)?.let { Value.Literal(it) }
            override fun requireNumber(key: String) = number(key) ?: fail("missing $key")
            override fun vec3(key: String): Value<Vec3>? = null
            override fun boolValue(key: String): Value<Boolean>? = null
            override fun point(key: String): PointRef? = null
            override fun stringList(key: String): List<String>? = null
            override fun ops(key: String): List<SkillOp> = emptyList()
            override fun raw(key: String): Any? = keys[key]
            override fun fail(message: String): Nothing = throw IllegalStateException(message)
        }
        val decoder = worldDecoders(com.astarworks.astera.core.application.skillengine.load.IBlockKeyCatalog.Unchecked).single()
        assertThat(decoder.section).isEqualTo(OpSection.GAMEPLAY)
        val op = decoder.decode("break_area", "dig", null, reader) as WorldOp.BreakArea
        assertThat(op.shape).isEqualTo(WorldOp.AreaShape.BOX)
        assertThat(op.toolClass).isEqualTo("SHOVEL")
        assertThat(op.blockDamage).isEqualTo(40)
        assertThat(op.breaksBlocks).isFalse()
        assertThat(op.at).isEqualTo(atCaster)
    }
}
