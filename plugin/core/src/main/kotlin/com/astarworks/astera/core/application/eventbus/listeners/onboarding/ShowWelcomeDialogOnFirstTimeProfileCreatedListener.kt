package com.astarworks.astera.core.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.application.port.outbound.IDialogGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerProfileService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogAfterAction
import com.astarworks.astera.core.domain.dialog.DialogBody
import com.astarworks.astera.core.domain.dialog.DialogButton
import com.astarworks.astera.core.domain.dialog.DialogId
import com.astarworks.astera.core.domain.dialog.DialogSpec
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * On [FirstTimeProfileCreated], show the configured welcome dialog to the
 * new player (Wave 5wireup, ADR-0116 §G3).
 *
 * # Why the listener composes the [DialogSpec] inline
 *
 * Phase 1 PoC has no `IDialogRegistry` yet — dialog content is not authored
 * through a content YAML registry the way items / skills / tutorials are.
 * The listener therefore reads the **opaque welcome dialog id** recorded by
 * [com.astarworks.astera.core.application.service.InMemoryPlayerProfileService.findWelcomeDialog]
 * (sourced from `content/balance/starter-grant.yaml`) and uses it as a
 * MessageKey discriminator so a translator can target a specific welcome
 * variant (`astera.dialog.welcome.<id>.title` etc.) without the listener
 * needing a registry round-trip.
 *
 * When the dialog content registry lands (post-Wave 5wireup), the listener
 * flips to `dialogRegistry.find(welcomeId)` and pushes the resolved spec
 * unchanged. No fallback shim per CLAUDE.md §Pre-release posture.
 *
 * # No-op paths
 *
 *  - No welcome dialog id recorded on the profile → DEBUG log, skip.
 *  - Gateway returns `UnknownPlayer` / `InvalidSpec` / `Storage` → WARN log,
 *    swallow. The dialog is best-effort UX, not gameplay-critical.
 */
public class ShowWelcomeDialogOnFirstTimeProfileCreatedListener(
    private val profileService: InMemoryPlayerProfileService,
    private val dialogGateway: IDialogGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: FirstTimeProfileCreated) {
        val welcomeId = profileService.findWelcomeDialog(event.playerId)
        if (welcomeId.isNullOrBlank()) {
            log.debug(
                "No welcome dialog recorded for {}; skipping welcome dialog.",
                event.playerId,
            )
            return
        }

        val spec = DialogSpec(
            id = DialogId.random(),
            titleKey = MessageKey("astera.dialog.welcome.$welcomeId.title"),
            body = listOf(
                DialogBody.PlainText(
                    messageKey = MessageKey("astera.dialog.welcome.$welcomeId.body"),
                ),
            ),
            buttons = listOf(
                DialogButton(
                    labelKey = MessageKey("astera.dialog.welcome.ok"),
                    intent = GuiIntent("dialog.close"),
                ),
            ),
            afterAction = DialogAfterAction.CloseDialog,
        )

        when (val outcome = dialogGateway.showDialog(event.playerId, spec)) {
            is Result.Success ->
                log.info(
                    "Showed welcome dialog '{}' to first-time player {}",
                    welcomeId,
                    event.playerId,
                )
            is Result.Failure ->
                log.warn(
                    "Show welcome dialog '{}' to {} failed: {}",
                    welcomeId,
                    event.playerId,
                    outcome.error,
                )
        }
    }
}
