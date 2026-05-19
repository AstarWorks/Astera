package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.application.gui.dialog.examples.playerSettingsDialog
import com.astarworks.astera.core.application.gui.dialog.examples.shopPurchaseConfirmDialog
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogAfterAction
import com.astarworks.astera.core.domain.dialog.DialogBody
import com.astarworks.astera.core.domain.dialog.DialogId
import com.astarworks.astera.core.domain.dialog.DialogInput
import com.astarworks.astera.core.domain.dialog.DialogResponse
import com.astarworks.astera.core.domain.event.DialogResponseReceived
import com.astarworks.astera.core.domain.event.DialogShown
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDialogGateway
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pure tests for the [DialogSpecBuilder] DSL (Wave 5i Worker γ, ADR-0096).
 *
 * Two-level coverage:
 *  1. DSL primitives — title/body/inputs/buttons + required-field guards.
 *  2. End-to-end integration through `FakeDialogGateway.simulateResponse`,
 *     which is the same code path use case tests will hit in Wave 6b.
 *
 * No Paper / Bukkit harness, no JOML, no I/O.
 */
class DialogSpecBuilderDslTest {

    private val playerId = PlayerId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    // ---------------------------------------------------------------------
    // 1. DSL builds a fully-populated spec
    // ---------------------------------------------------------------------

    @Test
    fun `dialogSpec builds with title, body, inputs, and buttons`() {
        val fixedId = DialogId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

        val spec = dialogSpec("astera.test.title") {
            id(fixedId)
            body {
                text("astera.test.line1")
            }
            inputs {
                text("name", "astera.test.name-label")
            }
            buttons {
                button("astera.test.ok") {
                    intent("test.ok")
                }
            }
        }

        assertThat(spec.id).isEqualTo(fixedId)
        assertThat(spec.titleKey.value).isEqualTo("astera.test.title")
        assertThat(spec.body).hasSize(1)
        assertThat(spec.inputs).hasSize(1)
        assertThat(spec.buttons).hasSize(1)
        // Default afterAction is CloseDialog (matches the domain default).
        assertThat(spec.afterAction).isEqualTo(DialogAfterAction.CloseDialog)
    }

    // ---------------------------------------------------------------------
    // 2. body { text(...) } produces DialogBody.PlainText (and itemPreview works)
    // ---------------------------------------------------------------------

    @Test
    fun `body text and itemPreview produce the right DialogBody variants in order`() {
        val spec = dialogSpec("astera.body.title") {
            body {
                text("astera.body.intro")
                itemPreview("iron-sword")
                text("astera.body.outro")
            }
            buttons {
                button("astera.body.close") { intent("gui.close") }
            }
        }

        assertThat(spec.body).hasSize(3)
        val first = spec.body[0] as DialogBody.PlainText
        val middle = spec.body[1] as DialogBody.ItemPreview
        val last = spec.body[2] as DialogBody.PlainText
        assertThat(first.messageKey.value).isEqualTo("astera.body.intro")
        assertThat(middle.itemId).isEqualTo(ItemId("iron-sword"))
        assertThat(last.messageKey.value).isEqualTo("astera.body.outro")
    }

    // ---------------------------------------------------------------------
    // 3. inputs { number(...) bool(...) } produces 2 inputs in declaration order
    // ---------------------------------------------------------------------

    @Test
    fun `inputs preserve declaration order across number and bool helpers`() {
        val spec = dialogSpec("astera.inputs.title") {
            inputs {
                number(
                    key = "qty",
                    labelKey = "astera.inputs.qty",
                    min = 1f,
                    max = 10f,
                ) {
                    initial(3f)
                    step(1f)
                }
                bool(
                    key = "remember",
                    labelKey = "astera.inputs.remember",
                    initial = true,
                )
            }
            buttons {
                button("astera.inputs.ok") { intent("test.ok") }
            }
        }

        assertThat(spec.inputs).hasSize(2)

        val number = spec.inputs[0] as DialogInput.NumberField
        assertThat(number.key).isEqualTo("qty")
        assertThat(number.min).isEqualTo(1f)
        assertThat(number.max).isEqualTo(10f)
        assertThat(number.initial).isEqualTo(3f)
        assertThat(number.step).isEqualTo(1f)

        val bool = spec.inputs[1] as DialogInput.BooleanField
        assertThat(bool.key).isEqualTo("remember")
        assertThat(bool.initial).isTrue()
    }

    // ---------------------------------------------------------------------
    // 4. buttons { button(...) } requires intent(...) — throws otherwise
    // ---------------------------------------------------------------------

    @Test
    fun `button without intent fails build with a helpful message`() {
        assertThatThrownBy {
            dialogSpec("astera.bad.title") {
                buttons {
                    button("astera.bad.broken") {
                        // No intent(...) call -- build must fail.
                    }
                }
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("astera.bad.broken")
            .hasMessageContaining("intent")
    }

    @Test
    fun `dialogSpec rejects blank title key at construction time`() {
        // MessageKey("") is rejected by the value class' regex; surface the
        // bad key right at dialogSpec entry rather than letting it slip past.
        assertThatThrownBy {
            dialogSpec("") {
                buttons {
                    button("astera.blank.close") { intent("gui.close") }
                }
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("MessageKey")
    }

    // ---------------------------------------------------------------------
    // 5. SingleSelect via DSL: initialKey must match an option
    // ---------------------------------------------------------------------

    @Test
    fun `select DSL produces SingleSelect with option list and initial`() {
        val spec = dialogSpec("astera.select.title") {
            inputs {
                select("lang", "astera.select.lang") {
                    option("en", "astera.select.en")
                    option("ja", "astera.select.ja")
                    initial("ja")
                }
            }
            buttons {
                button("astera.select.ok") { intent("test.ok") }
            }
        }

        val select = spec.inputs[0] as DialogInput.SingleSelect
        assertThat(select.key).isEqualTo("lang")
        assertThat(select.options).extracting("key").containsExactly("en", "ja")
        assertThat(select.initialKey).isEqualTo("ja")
    }

    // ---------------------------------------------------------------------
    // 6. shopPurchaseConfirmDialog example returns a valid DialogSpec
    // ---------------------------------------------------------------------

    @Test
    fun `shopPurchaseConfirmDialog example returns a usable DialogSpec`() {
        val spec = shopPurchaseConfirmDialog(
            offerId = "offer-42",
            itemId = ItemId("iron-sword"),
            maxStock = 64,
        )

        assertThat(spec.titleKey.value).isEqualTo("astera.shop.confirm.title")
        assertThat(spec.body).hasSize(2) // text + itemPreview
        assertThat(spec.inputs).hasSize(2) // qty + save-for-recent
        assertThat(spec.buttons).hasSize(2) // buy + cancel

        val qty = spec.inputs[0] as DialogInput.NumberField
        assertThat(qty.key).isEqualTo("quantity")
        assertThat(qty.min).isEqualTo(1f)
        assertThat(qty.max).isEqualTo(64f)
        assertThat(qty.initial).isEqualTo(1f)

        val saveRecent = spec.inputs[1] as DialogInput.BooleanField
        assertThat(saveRecent.key).isEqualTo("save-for-recent")
        assertThat(saveRecent.initial).isTrue()

        val buyButton = spec.buttons[0]
        assertThat(buyButton.labelKey.value).isEqualTo("astera.shop.confirm.buy-button")
        assertThat(buyButton.tooltipKey?.value).isEqualTo("astera.shop.confirm.buy-tooltip")
        assertThat(buyButton.intent.key).isEqualTo("shop.purchase.confirm")
        assertThat(buyButton.intent.payload).isEqualTo(mapOf("offer-id" to "offer-42"))

        val cancelButton = spec.buttons[1]
        assertThat(cancelButton.intent.key).isEqualTo("gui.close")
        assertThat(cancelButton.intent.payload).isEmpty()
    }

    // ---------------------------------------------------------------------
    // 7. playerSettingsDialog example returns a valid DialogSpec with a select
    // ---------------------------------------------------------------------

    @Test
    fun `playerSettingsDialog example wires select initial value through to spec`() {
        val spec = playerSettingsDialog(currentLocale = "ja", currentParticles = false)

        assertThat(spec.titleKey.value).isEqualTo("astera.settings.title")
        assertThat(spec.body).hasSize(1)
        assertThat(spec.inputs).hasSize(4) // display-name + language + particles + hud-scale
        assertThat(spec.buttons).hasSize(2)

        val displayName = spec.inputs[0] as DialogInput.TextField
        assertThat(displayName.key).isEqualTo("display-name")
        assertThat(displayName.maxLength).isEqualTo(32)
        assertThat(displayName.initial).isEqualTo("")

        val language = spec.inputs[1] as DialogInput.SingleSelect
        assertThat(language.key).isEqualTo("language")
        assertThat(language.options).extracting("key").containsExactly("en", "ja")
        assertThat(language.initialKey).isEqualTo("ja")

        val particles = spec.inputs[2] as DialogInput.BooleanField
        assertThat(particles.key).isEqualTo("particles-enabled")
        assertThat(particles.initial).isFalse() // mirrors `currentParticles = false`

        val hudScale = spec.inputs[3] as DialogInput.NumberField
        assertThat(hudScale.key).isEqualTo("hud-scale")
        assertThat(hudScale.min).isEqualTo(0.5f)
        assertThat(hudScale.max).isEqualTo(2.0f)
        assertThat(hudScale.initial).isEqualTo(1.0f)
        assertThat(hudScale.step).isEqualTo(0.1f)
    }

    // ---------------------------------------------------------------------
    // 8. Integration: FakeDialogGateway carries the spec + simulateResponse
    //    fires the right intent + inputs
    // ---------------------------------------------------------------------

    @Test
    fun `FakeDialogGateway carries shopPurchaseConfirmDialog and simulateResponse fires the buy intent`() {
        val bus = FakeDomainEventBus()
        val gateway = FakeDialogGateway(eventBus = bus)
        val spec = shopPurchaseConfirmDialog(
            offerId = "offer-42",
            itemId = ItemId("iron-sword"),
            maxStock = 8,
        )

        val captured = mutableListOf<DialogResponse>()
        val showResult = gateway.showDialog(
            playerId = playerId,
            spec = spec,
            onResponse = { captured += it },
        )

        // showDialog returns Success and records the call.
        assertThat(showResult).isInstanceOf(Result.Success::class.java)
        assertThat(gateway.showCalls).hasSize(1)
        assertThat(gateway.showCalls[0].spec.id).isEqualTo(spec.id)
        assertThat(bus.publishedOfType<DialogShown>()).hasSize(1)

        // Pretend the player typed "5" for quantity, kept save-for-recent default,
        // then clicked the buy button (index 0).
        gateway.simulateResponse(
            playerId = playerId,
            dialogId = spec.id,
            buttonIndex = 0,
            inputs = mapOf("quantity" to "5", "save-for-recent" to "true"),
        )

        // 1. onResponse callback fired exactly once with the right intent + inputs.
        assertThat(captured).hasSize(1)
        val response = captured[0]
        assertThat(response.dialogId).isEqualTo(spec.id)
        assertThat(response.triggeredButtonIntent.key).isEqualTo("shop.purchase.confirm")
        assertThat(response.triggeredButtonIntent.payload).isEqualTo(mapOf("offer-id" to "offer-42"))
        assertThat(response.inputValues).isEqualTo(
            mapOf("quantity" to "5", "save-for-recent" to "true"),
        )

        // 2. DialogResponseReceived event flowed through the bus with the same payload.
        val responseEvents = bus.publishedOfType<DialogResponseReceived>()
        assertThat(responseEvents).hasSize(1)
        assertThat(responseEvents[0].buttonIntent.key).isEqualTo("shop.purchase.confirm")
        assertThat(responseEvents[0].inputValues).containsEntry("quantity", "5")
    }
}
