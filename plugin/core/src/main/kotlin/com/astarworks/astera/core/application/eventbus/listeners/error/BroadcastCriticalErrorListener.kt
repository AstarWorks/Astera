package com.astarworks.astera.core.application.eventbus.listeners.error

import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementSpec
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementTrigger
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.port.outbound.error.IErrorReporter
import com.astarworks.astera.core.domain.admin.AnnouncementBroadcastKind
import com.astarworks.astera.core.domain.admin.AnnouncementId
import com.astarworks.astera.core.domain.error.ServerError
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Subscribes [IErrorReporter.subscribeCritical] and fans out each
 * [ServerError.Critical] as an `astera-announce`-style chat broadcast
 * (Wave L 5hardening-complete / ADR-0129).
 *
 * # Why announcement-system + not direct chat
 *
 * The Wave 5complete (ADR-0117 §Axis 2) [IScheduledAnnouncementSystem] is
 * already the canonical "broadcast to currently-online players" path:
 * it owns rate-limiting (Phase 6), audit emission, and the broadcast-kind
 * routing matrix that Wave 6b's Paper adapter will plug into. Routing
 * critical-error fan-out through it keeps **all** server-originated chat
 * on one fan-out lane with one audit signature.
 *
 * # Why not a direct IPermissionService → IPlayerGateway loop
 *
 * Two reasons:
 *
 * 1. Online-set enumeration is a Bukkit concern (IMcServer has no
 *    `onlinePlayers()` method in Phase 1 — it's per-ID lookup only). The
 *    announcement system already navigates that boundary; we don't want a
 *    second implementation.
 * 2. The broadcast-kind for critical errors should be `Chat` initially
 *    but admin tooling may flip a specific category to `Title` for
 *    out-of-band visibility. The announcement spec carries the kind as a
 *    first-class field — easier than a parallel HUD listener.
 *
 * # i18n key shape
 *
 * The broadcast message references
 * `astera.admin.error.critical.broadcast` and passes `{summary}` +
 * `{source}` as placeholders. The renderer (Wave 5b
 * `SimpleMessageRenderer`) substitutes them at delivery time. Today the
 * placeholders are not actually consumed by the announcement spec
 * (Phase 1 `AnnouncementSpec` carries only a `MessageKey`, no
 * placeholders) so the broadcast lands as a flat sentence keyed by the
 * locale; Wave 5q-follow-up adds placeholder propagation to the spec.
 * For now the structured detail is logged to the audit trail by
 * [com.astarworks.astera.core.application.service.error.InMemoryErrorReporter]
 * itself; the chat is the "look at the console / `/astera-audit`" pointer.
 *
 * # Schedule-failure handling
 *
 * `schedule()` returns `Failure(DuplicateId)` on the (theoretical) hash
 * collision of two `astera-error-<UUID>` ids in the same tick — we log
 * + drop and continue. The subscriber must never throw (the reporter
 * swallows handler exceptions, but defensive logging surfaces the issue
 * to operators).
 */
public class BroadcastCriticalErrorListener(
    private val errorReporter: IErrorReporter,
    private val announcer: IScheduledAnnouncementSystem,
    private val clock: Clock = Clock.systemUTC(),
    private val messageKey: MessageKey = DEFAULT_CRITICAL_KEY,
    private val broadcastKind: AnnouncementBroadcastKind = AnnouncementBroadcastKind.Chat,
) {

    private val log = LoggerFactory.getLogger(BroadcastCriticalErrorListener::class.java)

    private var subscription: IErrorReporter.Subscription? = null

    /**
     * Subscribe to critical-error reports. Returns the
     * [IErrorReporter.Subscription] so the caller (typically
     * `AsteraCorePlugin.onEnable`) can drop it into a cancel-on-disable
     * holder per the ADR-0123 §D listener-cleanup contract.
     */
    public fun subscribe(): IErrorReporter.Subscription {
        check(subscription == null) {
            "BroadcastCriticalErrorListener.subscribe() called twice; cancel the prior subscription first."
        }
        val sub = errorReporter.subscribeCritical { error -> handle(error) }
        subscription = sub
        return sub
    }

    public fun handle(error: ServerError.Critical) {
        val id = AnnouncementId("astera-error-" + UUID.randomUUID())
        val spec = AnnouncementSpec(
            id = id,
            messageKey = messageKey,
            trigger = AnnouncementTrigger.Once(at = Instant.now(clock)),
            broadcastKind = broadcastKind,
        )
        val outcome = announcer.schedule(spec)
        when (outcome) {
            is com.astarworks.astera.core.domain.Result.Success -> {
                log.info(
                    "Critical-error broadcast scheduled (id={}): summary='{}' source={}",
                    id.value,
                    error.summary,
                    error.source.identifier,
                )
            }
            is com.astarworks.astera.core.domain.Result.Failure -> {
                log.warn(
                    "Failed to schedule critical-error broadcast (id={}, err={}): {} / source={}",
                    id.value,
                    outcome.error::class.simpleName,
                    error.summary,
                    error.source.identifier,
                )
            }
        }
    }

    public companion object {
        /**
         * Default i18n key for the critical-error chat broadcast. Lives in
         * `content/languages/{en,ja}.yaml` under `astera.admin.error.critical.broadcast`;
         * Wave L 5hardening-complete adds both arms.
         */
        public val DEFAULT_CRITICAL_KEY: MessageKey = MessageKey("astera.admin.error.critical.broadcast")
    }
}
