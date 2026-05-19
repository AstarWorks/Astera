package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.event.DialogClosed
import com.astarworks.astera.core.domain.event.DialogResponseReceived
import com.astarworks.astera.core.domain.event.DialogShown
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDialogGateway
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5i, ADR-0096 — Dialog domain + FakeDialogGateway behavioural tests.
 *
 * Covers:
 *  - DialogSpec construction with every input variant + body element type
 *  - per-input init {} validation (rejects malformed config at construction)
 *  - FakeDialogGateway.simulateResponse callback + event publication
 *  - FakeDialogGateway.simulateForceClose event publication
 *  - DialogAfterAction.Nothing keeps the dialog open (no DialogClosed)
 */
class DialogTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val titleKey = MessageKey("astera.dialog.confirm.title")
    private val labelKey = MessageKey("astera.dialog.confirm.label")

    private fun confirmButton(intent: GuiIntent = GuiIntent("dialog.confirm")) =
        DialogButton(labelKey = labelKey, intent = intent)

    @Test
    fun `DialogSpec builds with every body and input variant plus defaults`() {
        val spec = DialogSpec(
            id = DialogId.random(),
            titleKey = titleKey,
            body = listOf(
                DialogBody.PlainText(MessageKey("astera.dialog.body.line1")),
                DialogBody.ItemPreview(ItemId("example-sword")),
            ),
            inputs = listOf(
                DialogInput.TextField(
                    key = "name",
                    labelKey = MessageKey("astera.dialog.field.name"),
                    initial = "anon",
                    maxLength = 32,
                    multiline = false,
                ),
                DialogInput.NumberField(
                    key = "qty",
                    labelKey = MessageKey("astera.dialog.field.qty"),
                    min = 1f,
                    max = 64f,
                    initial = 1f,
                    step = 1f,
                ),
                DialogInput.BooleanField(
                    key = "confirm",
                    labelKey = MessageKey("astera.dialog.field.confirm"),
                    initial = true,
                ),
                DialogInput.SingleSelect(
                    key = "tier",
                    labelKey = MessageKey("astera.dialog.field.tier"),
                    options = listOf(
                        DialogInput.SingleSelect.SelectOption(
                            key = "common",
                            labelKey = MessageKey("astera.dialog.tier.common"),
                        ),
                        DialogInput.SingleSelect.SelectOption(
                            key = "rare",
                            labelKey = MessageKey("astera.dialog.tier.rare"),
                        ),
                    ),
                    initialKey = "common",
                ),
            ),
            buttons = listOf(confirmButton()),
        )

        // Spot-check defaults survived the round-trip.
        assertThat(spec.afterAction).isEqualTo(DialogAfterAction.CloseDialog)
        assertThat(spec.inputs).hasSize(4)
        assertThat(spec.body).hasSize(2)
        val text = spec.inputs[0] as DialogInput.TextField
        assertThat(text.initial).isEqualTo("anon")
        assertThat(text.maxLength).isEqualTo(32)
        val sel = spec.inputs[3] as DialogInput.SingleSelect
        assertThat(sel.options).hasSize(2)
        assertThat(sel.initialKey).isEqualTo("common")
    }

    @Test
    fun `TextField rejects maxLength = 0`() {
        assertThatThrownBy {
            DialogInput.TextField(key = "x", labelKey = labelKey, maxLength = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxLength")
    }

    @Test
    fun `TextField rejects maxLength above 256`() {
        assertThatThrownBy {
            DialogInput.TextField(key = "x", labelKey = labelKey, maxLength = 1024)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `NumberField rejects min greater than max`() {
        assertThatThrownBy {
            DialogInput.NumberField(
                key = "x", labelKey = labelKey, min = 10f, max = 1f,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("min")
    }

    @Test
    fun `NumberField rejects initial outside range`() {
        assertThatThrownBy {
            DialogInput.NumberField(
                key = "x", labelKey = labelKey, min = 1f, max = 10f, initial = 100f,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("initial")
    }

    @Test
    fun `NumberField rejects non-positive step`() {
        assertThatThrownBy {
            DialogInput.NumberField(
                key = "x", labelKey = labelKey, min = 0f, max = 1f, step = 0f,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("step")
    }

    @Test
    fun `SingleSelect rejects empty options`() {
        assertThatThrownBy {
            DialogInput.SingleSelect(
                key = "x", labelKey = labelKey, options = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("options")
    }

    @Test
    fun `SingleSelect rejects initialKey not in options`() {
        assertThatThrownBy {
            DialogInput.SingleSelect(
                key = "x",
                labelKey = labelKey,
                options = listOf(
                    DialogInput.SingleSelect.SelectOption("a", labelKey),
                ),
                initialKey = "b",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("initialKey")
    }

    @Test
    fun `DialogButton rejects widthPx = 0`() {
        assertThatThrownBy {
            DialogButton(labelKey = labelKey, widthPx = 0, intent = GuiIntent("x"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("widthPx")
    }

    @Test
    fun `DialogButton rejects widthPx above 400`() {
        assertThatThrownBy {
            DialogButton(labelKey = labelKey, widthPx = 9999, intent = GuiIntent("x"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `FakeDialogGateway simulateResponse fires onResponse callback with correct intent and inputs`() {
        val bus = FakeDomainEventBus()
        val gateway = FakeDialogGateway(eventBus = bus)
        val captured = mutableListOf<DialogResponse>()

        val intent = GuiIntent("shop.purchase", mapOf("itemId" to "iron-sword"))
        val spec = DialogSpec(
            id = DialogId.random(),
            titleKey = titleKey,
            buttons = listOf(
                DialogButton(labelKey = labelKey, intent = GuiIntent("dialog.cancel")),
                DialogButton(labelKey = labelKey, intent = intent),
            ),
        )

        gateway.showDialog(playerId, spec, onResponse = { captured += it })
        gateway.simulateResponse(
            playerId = playerId,
            dialogId = spec.id,
            buttonIndex = 1,
            inputs = mapOf("qty" to "1"),
        )

        assertThat(captured).hasSize(1)
        assertThat(captured.single().triggeredButtonIntent).isEqualTo(intent)
        assertThat(captured.single().inputValues).containsExactlyEntriesOf(mapOf("qty" to "1"))
        assertThat(captured.single().dialogId).isEqualTo(spec.id)
        assertThat(captured.single().playerId).isEqualTo(playerId)
    }

    @Test
    fun `FakeDialogGateway simulateResponse publishes DialogResponseReceived plus DialogClosed`() {
        val bus = FakeDomainEventBus()
        val gateway = FakeDialogGateway(eventBus = bus)
        val intent = GuiIntent("dialog.ok")
        val spec = DialogSpec(
            id = DialogId.random(),
            titleKey = titleKey,
            buttons = listOf(DialogButton(labelKey = labelKey, intent = intent)),
            afterAction = DialogAfterAction.CloseDialog,
        )

        gateway.showDialog(playerId, spec)
        gateway.simulateResponse(
            playerId = playerId,
            dialogId = spec.id,
            buttonIndex = 0,
            inputs = mapOf("k" to "v"),
        )

        // DialogShown on show, DialogResponseReceived + DialogClosed(ButtonClick) on response.
        assertThat(bus.publishedOfType<DialogShown>()).hasSize(1)

        val responses = bus.publishedOfType<DialogResponseReceived>()
        assertThat(responses).hasSize(1)
        assertThat(responses.single().buttonIntent).isEqualTo(intent)
        assertThat(responses.single().inputValues).containsExactlyEntriesOf(mapOf("k" to "v"))
        assertThat(responses.single().dialogId).isEqualTo(spec.id)

        val closes = bus.publishedOfType<DialogClosed>()
        assertThat(closes).hasSize(1)
        assertThat(closes.single().reason).isEqualTo(DialogCloseReason.ButtonClick(intent))
        assertThat(closes.single().dialogId).isEqualTo(spec.id)
    }

    @Test
    fun `FakeDialogGateway simulateForceClose publishes DialogClosed with ForceClose`() {
        val bus = FakeDomainEventBus()
        val gateway = FakeDialogGateway(eventBus = bus)
        val spec = DialogSpec(
            id = DialogId.random(),
            titleKey = titleKey,
            buttons = listOf(confirmButton()),
        )

        gateway.showDialog(playerId, spec)
        gateway.simulateForceClose(playerId)

        val closes = bus.publishedOfType<DialogClosed>()
        assertThat(closes).hasSize(1)
        assertThat(closes.single().reason).isEqualTo(DialogCloseReason.ForceClose)
        assertThat(closes.single().playerId).isEqualTo(playerId)
        assertThat(closes.single().dialogId).isEqualTo(spec.id)

        // No response was synthesised, so no DialogResponseReceived must fire.
        assertThat(bus.publishedOfType<DialogResponseReceived>()).isEmpty()
    }

    @Test
    fun `FakeDialogGateway with afterAction Nothing does NOT publish DialogClosed on button click`() {
        val bus = FakeDomainEventBus()
        val gateway = FakeDialogGateway(eventBus = bus)
        val intent = GuiIntent("wizard.next")
        val spec = DialogSpec(
            id = DialogId.random(),
            titleKey = titleKey,
            buttons = listOf(DialogButton(labelKey = labelKey, intent = intent)),
            afterAction = DialogAfterAction.Nothing,
        )

        gateway.showDialog(playerId, spec)
        gateway.simulateResponse(playerId, spec.id, buttonIndex = 0)

        // Response fired, but the dialog stays open per afterAction = Nothing,
        // so no DialogClosed should be published.
        assertThat(bus.publishedOfType<DialogResponseReceived>()).hasSize(1)
        assertThat(bus.publishedOfType<DialogClosed>()).isEmpty()

        // And we can still respond a second time because the dialog is still open.
        gateway.simulateResponse(playerId, spec.id, buttonIndex = 0)
        assertThat(bus.publishedOfType<DialogResponseReceived>()).hasSize(2)
    }

    @Test
    fun `FakeDialogGateway simulateResponse throws when no matching dialog is open`() {
        val gateway = FakeDialogGateway()
        assertThatThrownBy {
            gateway.simulateResponse(
                playerId = playerId,
                dialogId = DialogId.random(),
                buttonIndex = 0,
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `FakeDialogGateway simulateResponse twice after CloseDialog throws on second call`() {
        val gateway = FakeDialogGateway()
        val spec = DialogSpec(
            id = DialogId.random(),
            titleKey = titleKey,
            buttons = listOf(confirmButton()),
            afterAction = DialogAfterAction.CloseDialog,
        )

        gateway.showDialog(playerId, spec)
        gateway.simulateResponse(playerId, spec.id, buttonIndex = 0)

        // The dialog auto-closed after the click; a second simulateResponse
        // must fail because it is no longer open.
        assertThatThrownBy {
            gateway.simulateResponse(playerId, spec.id, buttonIndex = 0)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `FakeDialogGateway records showCalls and closeCalls history`() {
        val gateway = FakeDialogGateway()
        val spec = DialogSpec(
            id = DialogId.random(),
            titleKey = titleKey,
            buttons = listOf(confirmButton()),
        )

        gateway.showDialog(playerId, spec)
        gateway.closeDialog(playerId)

        assertThat(gateway.showCalls).hasSize(1)
        assertThat(gateway.showCalls.single().playerId).isEqualTo(playerId)
        assertThat(gateway.showCalls.single().spec).isEqualTo(spec)
        assertThat(gateway.closeCalls).containsExactly(playerId)

        gateway.clear()
        assertThat(gateway.showCalls).isEmpty()
        assertThat(gateway.closeCalls).isEmpty()
    }
}
