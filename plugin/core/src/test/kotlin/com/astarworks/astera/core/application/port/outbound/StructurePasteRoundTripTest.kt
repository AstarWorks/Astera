package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeStructurePaste
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5g [IStructurePaste] paste → undo round-trip pin (ADR-0072).
 *
 * The Fake records every paste in an in-memory map; `undo` clears the entry
 * idempotently. The Wave 6b `PaperStructurePaste` (FAWE adapter) will honor
 * the same contract — these assertions are the cross-impl spec.
 */
internal class StructurePasteRoundTripTest {

    private val world = WorldId("test-world")
    private val offset = Vec3(0.0, 64.0, 0.0)
    private val schematic = Schematic(path = "stages/test/anomaly_paste.schem", size = Vec3(8.0, 4.0, 8.0), version = 2)

    @Test
    fun `paste then undo round-trip clears the recorded state`() {
        val paste = FakeStructurePaste()

        val pasteResult = paste.paste(schematic, world, offset)
        assertThat(pasteResult).isInstanceOf(Result.Success::class.java)
        val handle = pasteResult.getOrNull()!!

        // State present after paste.
        assertThat(paste.pasted).hasSize(1)
        val record = paste.pasted[handle]!!
        assertThat(record.schematic).isEqualTo(schematic)
        assertThat(record.world).isEqualTo(world)
        assertThat(record.offset).isEqualTo(offset)

        // Undo clears the record.
        val undoResult = paste.undo(handle)
        assertThat(undoResult).isInstanceOf(Result.Success::class.java)
        assertThat(paste.pasted).isEmpty()
    }

    @Test
    fun `each paste returns a distinct handle and handles are independently undoable`() {
        val paste = FakeStructurePaste()

        val handleA = (paste.paste(schematic, world, offset) as Result.Success).value
        val handleB = (paste.paste(schematic, world, Vec3(100.0, 64.0, 0.0)) as Result.Success).value
        assertThat(handleA).isNotEqualTo(handleB)
        assertThat(paste.pasted.keys).containsExactlyInAnyOrder(handleA, handleB)

        paste.undo(handleA)
        assertThat(paste.pasted.keys).containsExactly(handleB)
    }

    @Test
    fun `undo on an unknown handle is idempotent success`() {
        val paste = FakeStructurePaste()
        val handle = (paste.paste(schematic, world, offset) as Result.Success).value

        // First undo: removes.
        assertThat(paste.undo(handle)).isInstanceOf(Result.Success::class.java)
        // Second undo on the same (now-unknown) handle: still success.
        assertThat(paste.undo(handle)).isInstanceOf(Result.Success::class.java)
    }
}
