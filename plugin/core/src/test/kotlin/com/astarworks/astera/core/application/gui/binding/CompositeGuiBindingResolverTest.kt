package com.astarworks.astera.core.application.gui.binding

import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CompositeGuiBindingResolverTest {

    private class StaticResolver(
        private val prefix: String,
        private val itemId: String,
    ) : IGuiBindingResolver {
        override fun resolve(binding: BindingKey, viewer: PlayerId?): ResolvedReactiveSlot? =
            if (binding.value.startsWith(prefix)) ResolvedReactiveSlot(itemId = ItemId(itemId)) else null
    }

    private fun pid(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `returns first non-null result`() {
        val composite = CompositeGuiBindingResolver(
            StaticResolver("a.", "alpha"),
            StaticResolver("b.", "bravo"),
        )

        val result = composite.resolve(BindingKey("b.x"), pid())

        assertThat(result?.itemId?.value).isEqualTo("bravo")
    }

    @Test
    fun `returns null when no delegate matches`() {
        val composite = CompositeGuiBindingResolver(
            StaticResolver("a.", "alpha"),
            StaticResolver("b.", "bravo"),
        )
        assertThat(composite.resolve(BindingKey("c.x"), pid())).isNull()
    }

    @Test
    fun `earlier delegate wins on overlapping prefix`() {
        val composite = CompositeGuiBindingResolver(
            StaticResolver("a.", "first"),
            StaticResolver("a.", "second"),
        )
        assertThat(composite.resolve(BindingKey("a.x"), pid())?.itemId?.value).isEqualTo("first")
    }

    @Test
    fun `empty composite returns null`() {
        val composite = CompositeGuiBindingResolver(emptyList())
        assertThat(composite.resolve(BindingKey("a.x"), pid())).isNull()
    }
}
