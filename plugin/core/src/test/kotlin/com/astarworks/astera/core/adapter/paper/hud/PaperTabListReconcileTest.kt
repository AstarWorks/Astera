package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.TabListEntry
import com.astarworks.astera.core.adapter.paper.packetevents.ITabListPacketSender
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.assertj.core.api.Assertions.assertThat
import org.bukkit.Server
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * Lifecycle tests for [PaperTabList]'s synthetic / offline-subject phantom-row
 * reconcile (Wave 6c-stub-packetevents-D, ADR-0173).
 *
 * [TabListPhantomUuidTest] pins the UUID derivation in isolation; this pins the
 * behaviour built on top of it — the ADD-on-push / REMOVE-on-dropout diff in
 * `pushEntries`, the online-vs-synthetic branch that feeds it, and the
 * vanilla-Paper (no sender) drop. Without these, the only new code in the wave
 * with branching logic would ship untested.
 *
 * Bukkit's `Server` / `Player` are too wide to hand-roll and no Bukkit mock lib
 * is on the `:plugin:core` test classpath, so a `java.lang.reflect.Proxy` stubs
 * the handful of methods `pushEntries` touches — `Server.getPlayer(UUID)`,
 * `Server.getPlayerExact(String)`, `Player.getUniqueId()` — same idiom as
 * [com.astarworks.astera.core.adapter.paper.animation.PaperPlayerAnimationGatewayTest].
 */
class PaperTabListReconcileTest {

    private val viewerUuid = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val viewer = PlayerId(viewerUuid)

    private fun phantom(name: String): UUID = phantomTabListUuid(viewerUuid, name)

    // ---- recording sender ------------------------------------------------

    private data class Add(val viewer: UUID, val entryUuid: UUID, val name: String, val display: String, val latency: Int)
    private data class Remove(val viewer: UUID, val entryUuid: UUID)
    private data class DisplayUpdate(val viewer: UUID, val subject: UUID, val display: String)
    private data class LatencyUpdate(val viewer: UUID, val subject: UUID, val latency: Int)

    private class RecordingSender : ITabListPacketSender {
        val adds = mutableListOf<Add>()
        val removes = mutableListOf<Remove>()
        val displayUpdates = mutableListOf<DisplayUpdate>()
        val latencyUpdates = mutableListOf<LatencyUpdate>()

        override fun sendDisplayNameUpdate(viewer: Player, subjectUuid: UUID, displayName: Component) {
            displayUpdates += DisplayUpdate(viewer.uniqueId, subjectUuid, plain(displayName))
        }

        override fun sendLatencyUpdate(viewer: Player, subjectUuid: UUID, latencyMs: Int) {
            latencyUpdates += LatencyUpdate(viewer.uniqueId, subjectUuid, latencyMs)
        }

        override fun sendAddEntry(viewer: Player, entryUuid: UUID, name: String, displayName: Component, latencyMs: Int) {
            adds += Add(viewer.uniqueId, entryUuid, name, plain(displayName), latencyMs)
        }

        override fun sendRemoveEntry(viewer: Player, entryUuid: UUID) {
            removes += Remove(viewer.uniqueId, entryUuid)
        }
    }

    // ---- tests -----------------------------------------------------------

    @Test
    fun `synthetic entries are injected as phantom rows, none removed on first push`() {
        val sender = RecordingSender()
        val tabList = PaperTabList(serverStub(), null, sender)

        tabList.setPlayerEntries(viewer, listOf(entry("npc-a", ping = 40), entry("npc-b", ping = 80)))

        assertThat(sender.adds).containsExactly(
            Add(viewerUuid, phantom("npc-a"), "npc-a", "npc-a", 40),
            Add(viewerUuid, phantom("npc-b"), "npc-b", "npc-b", 80),
        )
        assertThat(sender.removes).isEmpty()
    }

    @Test
    fun `an entry dropping out of the next push removes exactly its phantom row`() {
        val sender = RecordingSender()
        val tabList = PaperTabList(serverStub(), null, sender)

        tabList.setPlayerEntries(viewer, listOf(entry("npc-a"), entry("npc-b")))
        sender.adds.clear()
        tabList.setPlayerEntries(viewer, listOf(entry("npc-a")))

        // npc-b dropped → its phantom is REMOVE'd; npc-a stays → re-ADD'd, not removed.
        assertThat(sender.removes).containsExactly(Remove(viewerUuid, phantom("npc-b")))
        assertThat(sender.adds.map { it.entryUuid }).containsExactly(phantom("npc-a"))
    }

    @Test
    fun `clearing the entry list removes every phantom row`() {
        val sender = RecordingSender()
        val tabList = PaperTabList(serverStub(), null, sender)

        tabList.setPlayerEntries(viewer, listOf(entry("npc-a"), entry("npc-b")))
        tabList.setPlayerEntries(viewer, emptyList())

        assertThat(sender.removes).containsExactlyInAnyOrder(
            Remove(viewerUuid, phantom("npc-a")),
            Remove(viewerUuid, phantom("npc-b")),
        )
    }

    @Test
    fun `re-pushing the same synthetic set re-adds idempotently and removes nothing`() {
        val sender = RecordingSender()
        val tabList = PaperTabList(serverStub(), null, sender)

        tabList.setPlayerEntries(viewer, listOf(entry("npc-a")))
        tabList.setPlayerEntries(viewer, listOf(entry("npc-a")))

        // ADD re-sent every push (idempotent refresh); the stable row is never removed.
        assertThat(sender.adds.map { it.entryUuid }).containsExactly(phantom("npc-a"), phantom("npc-a"))
        assertThat(sender.removes).isEmpty()
    }

    @Test
    fun `online subjects use the display+latency path while synthetics use phantom ADD`() {
        val sender = RecordingSender()
        val onlineUuid = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
        val tabList = PaperTabList(serverStub(online = mapOf("online-1" to onlineUuid)), null, sender)

        tabList.setPlayerEntries(
            viewer,
            listOf(
                TabListEntry("online-1", MessageKey("astera.tab.online"), 30),
                TabListEntry("npc-1", MessageKey("astera.tab.npc"), 90),
            ),
        )

        assertThat(sender.displayUpdates).containsExactly(DisplayUpdate(viewerUuid, onlineUuid, "astera.tab.online"))
        assertThat(sender.latencyUpdates).containsExactly(LatencyUpdate(viewerUuid, onlineUuid, 30))
        assertThat(sender.adds).containsExactly(Add(viewerUuid, phantom("npc-1"), "npc-1", "astera.tab.npc", 90))
        assertThat(sender.removes).isEmpty()
    }

    @Test
    fun `without a sender (vanilla Paper) synthetic rows are dropped but still snapshotted`() {
        val tabList = PaperTabList(serverStub(), null, null)

        // No sender → no packets to assert; the contract is "does not throw" and
        // the snapshot still records what was requested (offline rows are merely
        // not rendered, not forgotten).
        tabList.setPlayerEntries(viewer, listOf(entry("npc-a"), entry("npc-b")))

        assertThat(tabList.currentEntries(viewer).map { it.name }).containsExactly("npc-a", "npc-b")
    }

    // ---- helpers / stubs -------------------------------------------------

    private fun entry(name: String, ping: Int = 0): TabListEntry = TabListEntry(name, displayName = null, pingMs = ping)

    /**
     * Proxy [Server] exposing only the two lookups `pushEntries` performs:
     * `getPlayer(UUID)` → the viewer (for the requesting `playerId`), and
     * `getPlayerExact(String)` → an online subject from [online] or `null`
     * (which routes the entry down the synthetic / phantom path).
     */
    private fun serverStub(online: Map<String, UUID> = emptyMap()): Server {
        val onlinePlayers = online.mapValues { (_, uuid) -> playerStub(uuid) }
        return Proxy.newProxyInstance(
            Server::class.java.classLoader,
            arrayOf(Server::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "getPlayer" -> if (args?.getOrNull(0) == viewerUuid) playerStub(viewerUuid) else null
                    "getPlayerExact" -> onlinePlayers[args?.getOrNull(0) as? String]
                    "toString" -> "serverStub"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("PaperTabListReconcileTest: unexpected Server.${method.name}")
                }
            },
        ) as Server
    }

    private fun playerStub(uuid: UUID): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getUniqueId" -> uuid
                "playerListName" -> null // void setter, only hit on the vanilla online path
                "toString" -> "playerStub($uuid)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("PaperTabListReconcileTest: unexpected Player.${method.name}")
            }
        },
    ) as Player

    private companion object {
        private fun plain(component: Component): String = (component as TextComponent).content()
    }
}
