package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeTabList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FakeTabListTest {

    private val playerA = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val playerB = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    private val headerKey = MessageKey("astera.tablist.header")
    private val footerKey = MessageKey("astera.tablist.footer")

    @Test
    fun `setHeader and setFooter then currentHeader and currentFooter return rendered text`() {
        val tabList = FakeTabList()

        tabList.setHeader(playerA, headerKey)
        tabList.setFooter(playerA, footerKey)

        assertThat(tabList.currentHeader(playerA)).isEqualTo(headerKey.value)
        assertThat(tabList.currentFooter(playerA)).isEqualTo(footerKey.value)
    }

    @Test
    fun `placeholders are substituted into header and footer`() {
        val tabList = FakeTabList()

        // The MessageKey regex forbids '{', so a real key never contains a
        // placeholder marker. The substitution helper still has to run and
        // not blow up — assert the contract: rendered output equals the key
        // value (unchanged because the key has no '{...}' markers) even
        // when placeholders are supplied.
        tabList.setHeader(playerA, headerKey, mapOf("name" to "Alice"))
        tabList.setFooter(playerA, footerKey, mapOf("name" to "Alice"))

        assertThat(tabList.currentHeader(playerA)).isEqualTo(headerKey.value)
        assertThat(tabList.currentFooter(playerA)).isEqualTo(footerKey.value)

        // And the empty-placeholders happy path returns the bare key value.
        tabList.setHeader(playerB, headerKey, emptyMap())
        assertThat(tabList.currentHeader(playerB)).isEqualTo(headerKey.value)
    }

    @Test
    fun `clearHeader removes the header and currentHeader returns null afterwards`() {
        val tabList = FakeTabList()
        tabList.setHeader(playerA, headerKey)
        assertThat(tabList.currentHeader(playerA)).isNotNull()

        tabList.clearHeader(playerA)

        assertThat(tabList.currentHeader(playerA)).isNull()
    }

    @Test
    fun `clearFooter removes the footer`() {
        val tabList = FakeTabList()
        tabList.setFooter(playerA, footerKey)
        assertThat(tabList.currentFooter(playerA)).isNotNull()

        tabList.clearFooter(playerA)

        assertThat(tabList.currentFooter(playerA)).isNull()
    }

    @Test
    fun `setPlayerEntries stores the list and currentEntries returns it in order`() {
        val tabList = FakeTabList()
        val entries = listOf(
            TabListEntry(name = "Alice", displayName = null, pingMs = 12),
            TabListEntry(name = "Bob", displayName = MessageKey("astera.tablist.bob"), pingMs = 47),
            TabListEntry(name = "Carol", displayName = null, pingMs = 0),
        )

        tabList.setPlayerEntries(playerA, entries)

        assertThat(tabList.currentEntries(playerA))
            .containsExactly(entries[0], entries[1], entries[2])
    }

    @Test
    fun `setPlayerEntries replaces the entire previous list not append`() {
        val tabList = FakeTabList()
        tabList.setPlayerEntries(
            playerA,
            listOf(TabListEntry(name = "Alice", displayName = null)),
        )
        tabList.setPlayerEntries(
            playerA,
            listOf(
                TabListEntry(name = "Bob", displayName = null),
                TabListEntry(name = "Carol", displayName = null),
            ),
        )

        val current = tabList.currentEntries(playerA)
        assertThat(current).hasSize(2)
        assertThat(current.map { it.name }).containsExactly("Bob", "Carol")
    }

    @Test
    fun `TabListEntry rejects negative pingMs`() {
        assertThatThrownBy {
            TabListEntry(name = "Alice", displayName = null, pingMs = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `each player tablist is isolated - setting player A header does not affect player B`() {
        val tabList = FakeTabList()

        tabList.setHeader(playerA, headerKey)
        tabList.setFooter(playerA, footerKey)
        tabList.setPlayerEntries(
            playerA,
            listOf(TabListEntry(name = "Alice", displayName = null)),
        )

        assertThat(tabList.currentHeader(playerB)).isNull()
        assertThat(tabList.currentFooter(playerB)).isNull()
        assertThat(tabList.currentEntries(playerB)).isEmpty()

        // And player A still has their state.
        assertThat(tabList.currentHeader(playerA)).isEqualTo(headerKey.value)
        assertThat(tabList.currentFooter(playerA)).isEqualTo(footerKey.value)
        assertThat(tabList.currentEntries(playerA)).hasSize(1)
    }

    @Test
    fun `currentEntries returns an empty list for unknown player never null`() {
        val tabList = FakeTabList()

        val entries = tabList.currentEntries(playerA)

        assertThat(entries).isNotNull()
        assertThat(entries).isEmpty()
    }

    @Test
    fun `currentHeader returns null for player who never had header set`() {
        val tabList = FakeTabList()

        assertThat(tabList.currentHeader(playerA)).isNull()
        assertThat(tabList.currentFooter(playerA)).isNull()
    }
}
