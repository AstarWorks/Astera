package com.astarworks.astera.core.application.gui.dialog.examples

import com.astarworks.astera.core.application.gui.dialogSpec
import com.astarworks.astera.core.domain.dialog.DialogAfterAction
import com.astarworks.astera.core.domain.dialog.DialogSpec

/**
 * Realistic example: per-player settings form (Wave 5i Worker γ, ADR-0096).
 *
 * Opens on `/astera settings` (or any equivalent command). Covers the
 * Phase 1 player-tunable preferences:
 *  - `display-name` — opt-in display name override (free-form text, ≤32 chars).
 *  - `language` — i18n locale (single-select over supported locales).
 *  - `particles-enabled` — toggle elaborate skill / hit-feedback particles.
 *  - `hud-scale` — scale factor for the custom HUD overlay (0.5x..2.0x).
 *
 * Form initial values come from the caller's current settings snapshot so
 * the dialog reflects the player's existing config rather than resetting to
 * defaults each open.
 *
 * On `astera.settings.save` click: intent `settings.save` fires with no
 * payload; the form values arrive in `DialogResponse.inputValues`. The
 * use case (Wave 6b `PlayerSettingsUpdateUseCase`, future wave) reads each
 * input key by name and persists the result.
 *
 * On cancel: intent `gui.close`; afterAction closes the dialog regardless.
 *
 * Intended caller wiring:
 * ```
 * dialogGateway.showDialog(
 *     playerId = playerId,
 *     spec = playerSettingsDialog(currentLocale = "ja", currentParticles = true),
 *     onResponse = { resp ->
 *         if (resp.triggeredButtonIntent.key == "settings.save") {
 *             playerSettingsUseCase.update(playerId, resp.inputValues)
 *         }
 *     },
 * )
 * ```
 */
public fun playerSettingsDialog(
    currentLocale: String,
    currentParticles: Boolean,
): DialogSpec = dialogSpec("astera.settings.title") {
    body {
        text("astera.settings.intro")
    }
    inputs {
        text(
            key = "display-name",
            labelKey = "astera.settings.display-name",
        ) {
            initial("")
            maxLength(32)
        }
        select(
            key = "language",
            labelKey = "astera.settings.language",
        ) {
            option("en", "astera.settings.lang.en")
            option("ja", "astera.settings.lang.ja")
            initial(currentLocale)
        }
        bool(
            key = "particles-enabled",
            labelKey = "astera.settings.particles",
            initial = currentParticles,
        )
        number(
            key = "hud-scale",
            labelKey = "astera.settings.hud-scale",
            min = 0.5f,
            max = 2.0f,
        ) {
            initial(1.0f)
            step(0.1f)
        }
    }
    buttons {
        button("astera.settings.save") {
            intent("settings.save")
        }
        button("astera.settings.cancel") {
            intent("gui.close")
        }
    }
    afterAction(DialogAfterAction.CloseDialog)
}
