package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class GuiModalSpecTest {

    @Test
    fun `Confirmation carries i18n keys and two intents`() {
        val spec = GuiModalSpec.Confirmation(
            titleKey = MessageKey("astera.modal.confirm.title"),
            messageKey = MessageKey("astera.modal.confirm.body"),
            onConfirm = GuiIntent("shop.purchase.confirm"),
            onCancel = GuiIntent("shop.purchase.cancel"),
        )
        assertThat(spec.onConfirm.key).isEqualTo("shop.purchase.confirm")
        assertThat(spec.onCancel.key).isEqualTo("shop.purchase.cancel")
    }

    @Test
    fun `Notification accepts null dismissAfterTicks`() {
        val spec = GuiModalSpec.Notification(
            titleKey = MessageKey("astera.toast.title"),
            messageKey = MessageKey("astera.toast.body"),
            dismissAfterTicks = null,
        )
        assertThat(spec.dismissAfterTicks).isNull()
    }

    @Test
    fun `Notification rejects negative dismissAfterTicks`() {
        assertThatIllegalArgumentException().isThrownBy {
            GuiModalSpec.Notification(
                titleKey = MessageKey("a"),
                messageKey = MessageKey("b"),
                dismissAfterTicks = -1L,
            )
        }
    }

    @Test
    fun `QuickbarSelector requires non-empty slots`() {
        assertThatIllegalArgumentException().isThrownBy {
            GuiModalSpec.QuickbarSelector(slots = emptyList(), onSelect = GuiIntent("k"))
        }
    }

    @Test
    fun `QuickbarSelector enforces MAX_QUICKBAR_SLOTS`() {
        val tooMany = (0..GuiModalSpec.QuickbarSelector.MAX_QUICKBAR_SLOTS)
            .map { GuiSlot.Border(ItemId("filler")) }
        assertThatIllegalArgumentException().isThrownBy {
            GuiModalSpec.QuickbarSelector(slots = tooMany, onSelect = GuiIntent("k"))
        }
    }

    @Test
    fun `Custom wraps full GuiSpec`() {
        val inner = GuiSpec.build(
            id = GuiId("inner"),
            title = MessageKey("astera.gui.inner"),
            size = GuiSize.CHEST_27,
        ) {}
        val spec = GuiModalSpec.Custom(content = inner)
        assertThat(spec.content.id).isEqualTo(GuiId("inner"))
    }
}
