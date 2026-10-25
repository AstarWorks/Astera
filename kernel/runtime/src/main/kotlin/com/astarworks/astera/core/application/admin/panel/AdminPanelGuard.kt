package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.audit.AuditEventId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Permission gate + audit trail shared by every `admin.*` panel intent handler
 * (F1-D, ADR-0183).
 *
 * The GUI itself is unauthenticated plumbing — anyone can hold an inventory
 * that was opened for them, and permissions can be revoked while a panel is
 * open — so **each click is re-checked**: handlers are registered through
 * [guarded], which drops the click (with a localised denial message) unless
 * the clicker holds [PANEL_PERMISSION]. State-changing handlers then call
 * [record] so the action lands in [IAuditLogRepository].
 *
 * Registered in the `ServiceRegistry` by Core; feature plugins resolve it with
 * `services.getOrThrow<AdminPanelGuard>()` (no cross-feature import).
 */
public class AdminPanelGuard(
    private val permissions: IPermissionService,
    private val messages: IMessageRenderer,
    private val audit: IAuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
    /** Server operators (Paper `op`) may use the panel without an Astera role. */
    private val isOperator: (PlayerId) -> Boolean = { false },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Whether [playerId] holds [PANEL_PERMISSION] or is a server operator. No messaging. */
    public fun canUse(playerId: PlayerId): Boolean =
        permissions.check(playerId, PANEL_PERMISSION) || isOperator(playerId)

    /** `true` when [player] may use the panel. Sends the denial message otherwise. */
    public fun authorize(player: IMcPlayer): Boolean {
        if (canUse(player.id)) return true
        log.warn("Admin panel intent denied for {} ({})", player.name, player.id)
        tell(player, K_DENIED, mapOf("permission" to PANEL_PERMISSION.id))
        return false
    }

    /** Wrap [handler] so it only runs for authorised clickers. */
    public fun guarded(handler: (GuiIntent, IMcPlayer) -> Unit): (GuiIntent, IMcPlayer) -> Unit =
        { intent, player -> if (authorize(player)) handler(intent, player) }

    /** Append an [AuditEvent.AdminActionPerformed] for [actor]. Never throws into the click path. */
    public fun record(actor: IMcPlayer, action: String, target: String? = null, detail: String = "") {
        runCatching {
            audit.write(
                AuditEvent.AdminActionPerformed(
                    id = AuditEventId.random(),
                    actor = actor.id,
                    action = action,
                    target = target,
                    detail = detail,
                    occurredAt = clock.instant(),
                ),
            )
        }.onFailure { log.warn("Admin audit write failed for action {}", action, it) }
    }

    /** Send a localised chat line to [player]. */
    public fun tell(player: IMcPlayer, key: MessageKey, args: Map<String, String> = emptyMap()) {
        player.sendMessage(messages.render(player.id, key, args))
    }

    public companion object {
        public val PANEL_PERMISSION: Permission = Permission("astera.admin.panel")
        public val K_DENIED: MessageKey = MessageKey("astera.admin.panel.denied")
    }
}
