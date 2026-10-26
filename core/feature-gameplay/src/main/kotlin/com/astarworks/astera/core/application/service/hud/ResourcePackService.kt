package com.astarworks.astera.core.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.admin.AdminOnlinePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * The resource pack this server pushes, re-readable at runtime (F3-9, ADR-0224).
 *
 * The pack spec used to be read from the environment once at boot, so a rebuilt
 * pack (new sha1) needed a backend restart. [reload] runs [loader] again (the
 * same `ResourcePackEnv.load`: URL from env, sha1 fetched from the pack host),
 * swaps the active spec and, when the spec changed (or `force`), re-pushes it to
 * every online player. The client sees the same pack id (Paper derives it from
 * the URL) with a new hash and replaces the pack in place.
 *
 * Joins read [current] on each join, so players who connect after a reload get
 * the new pack without any further action.
 */
public class ResourcePackService(
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
    initial: ResourcePackSpec?,
    private val loader: () -> ResourcePackSpec?,
    private val hud: IHudGateway,
    private val onlinePlayers: () -> List<AdminOnlinePlayer>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val active = AtomicReference(initial)

    /** What `PlayerJoinedServer` pushes right now; `null` = no pack configured. */
    public fun current(): ResourcePackSpec? = active.get()

    /** Outcome of one [reload]. [pushed] is the number of online players the new spec was sent to. */
    public data class Report(
        public val previousSha1: String?,
        public val sha1: String,
        public val changed: Boolean,
        public val pushed: Int,
    )

    /**
     * Pushes the active spec to everyone online, so every client reports its pack status again. Called once after a
     * plugin reload: the new process has no memory of who already applied the pack, and the HP bar / icons stay in
     * their text fallback for a player whose client never re-reports.
     */
    public fun repushToOnline(): Int {
        val spec = active.get() ?: return 0
        var pushed = 0
        for (player in onlinePlayers()) {
            when (val outcome = hud.pushResourcePack(player.id, spec)) {
                is Result.Success -> pushed++
                is Result.Failure -> log.warn("Resource pack re-push failed for {}: {}", player.name, outcome.error)
            }
        }
        return pushed
    }

    /**
     * Re-read the pack spec. `null` = it could not be read (URL unset, sha1 file
     * unreachable); the previous spec stays active so a half-finished pack
     * rebuild never switches the server to "no pack".
     */
    public fun reload(force: Boolean = false): Report? {
        val fresh = loader() ?: return null
        val previous = active.getAndSet(fresh)
        val changed = previous != fresh
        var pushed = 0
        if (changed || force) {
            for (player in onlinePlayers()) {
                scheduler.run(com.astarworks.astera.core.application.port.outbound.ExecutionScope.Entity(player.id.uuid), kotlin.time.Duration.ZERO) {
                    val outcome = hud.pushResourcePack(player.id, fresh)
                    if (outcome is Result.Failure) log.warn("Resource pack re-push failed for {}: {}", player.name, outcome.error)
                }
                pushed++
            }
        }
        log.info(
            "Resource pack reloaded: {} -> {} (changed={}, re-pushed to {} player(s)).",
            previous?.sha1Hash ?: "none", fresh.sha1Hash, changed, pushed,
        )
        return Report(previous?.sha1Hash, fresh.sha1Hash, changed, pushed)
    }
}
