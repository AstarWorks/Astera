package com.astarworks.astera.core.domain.dialog

/**
 * What happens to the dialog after a button is clicked
 * (Paper `Dialog.Builder.afterAction`).
 *
 * - [Nothing] — dialog stays open (use case: multi-step wizard within
 *   one dialog frame)
 * - [CloseDialog] — close on any button click (default, typical confirmation
 *   prompt)
 * - [WaitForResponse] — close only after the button's intent handler
 *   confirms completion (advanced; used by long-running operations with
 *   progress)
 */
public enum class DialogAfterAction {
    Nothing,
    CloseDialog,
    WaitForResponse,
}
