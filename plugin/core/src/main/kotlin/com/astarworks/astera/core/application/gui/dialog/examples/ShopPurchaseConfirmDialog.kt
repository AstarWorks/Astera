package com.astarworks.astera.core.application.gui.dialog.examples

import com.astarworks.astera.core.application.gui.dialogSpec
import com.astarworks.astera.core.domain.dialog.DialogSpec
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Realistic example: "Are you sure you want to buy N × {item}?" prompt
 * (Wave 5i Worker γ, ADR-0096).
 *
 * This dialog opens when a player clicks "buy" on a shop offer that needs
 * confirmation (high-cost items, soulbound items, bulk purchases). The form
 * lets the player adjust the quantity within stock limits and optionally
 * remember the purchase as a "recent buy" for one-click repeat next time.
 *
 * On `astera.shop.confirm.buy-button` click:
 *  - Intent `shop.purchase.confirm` fires with payload `{"offer-id": offerId}`.
 *  - The `FakeDialogGateway.simulateResponse(... inputs = ...)` merges the
 *    `quantity` + `save-for-recent` form values into the response — the use
 *    case parses them out of `DialogResponse.inputValues`.
 *
 * On cancel: intent `gui.close` fires (a no-op convention recognised by
 * `DefaultGuiIntentRouter`); dialog auto-closes per the
 * `DialogAfterAction.CloseDialog` default.
 *
 * Intended caller wiring (Wave 6b):
 * ```
 * dialogGateway.showDialog(
 *     playerId = player.id,
 *     spec = shopPurchaseConfirmDialog(offer.id, offer.itemId, offer.remainingStock),
 *     onResponse = { resp ->
 *         when (resp.triggeredButtonIntent.key) {
 *             "shop.purchase.confirm" -> shopPurchaseUseCase.run(...)
 *             "gui.close"             -> { /* nothing */ }
 *         }
 *     },
 * )
 * ```
 */
public fun shopPurchaseConfirmDialog(
    offerId: String,
    itemId: ItemId,
    maxStock: Int,
): DialogSpec {
    require(maxStock >= 1) {
        "shopPurchaseConfirmDialog: maxStock must be >= 1, was $maxStock (an out-of-stock offer should not open the confirm dialog at all)"
    }
    return dialogSpec("astera.shop.confirm.title") {
        body {
            text("astera.shop.confirm.prompt")
            itemPreview(itemId)
        }
        inputs {
            number(
                key = "quantity",
                labelKey = "astera.shop.confirm.quantity-label",
                min = 1f,
                max = maxStock.toFloat(),
            ) {
                initial(1f)
                step(1f)
            }
            bool(
                key = "save-for-recent",
                labelKey = "astera.shop.confirm.save-recent",
                initial = true,
            )
        }
        buttons {
            button("astera.shop.confirm.buy-button") {
                tooltip("astera.shop.confirm.buy-tooltip")
                intent("shop.purchase.confirm") {
                    "offer-id"(offerId)
                }
            }
            button("astera.shop.confirm.cancel-button") {
                intent("gui.close")
            }
        }
    }
}
