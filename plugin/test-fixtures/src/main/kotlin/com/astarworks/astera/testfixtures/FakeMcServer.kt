package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.time.Duration

/**
 * In-memory [IMcServer] for use case tests that need to bridge
 * `PlayerId → IMcPlayer` (e.g. weapons' `GiveWeaponUseCase` resolves the
 * target IMcPlayer through this port to hand to IWeaponDelivery).
 *
 * Tests use [register] to populate `(id, IMcPlayer)` pairs; `findPlayer`
 * and `findPlayerByName` return them. The shipped [FakeMcPlayer] is a
 * minimal IMcPlayer that records `sendMessage` / `giveItem` /
 * `sendActionBar` / `sendTitle` calls.
 *
 * Promoted from `plugin/weapons/src/test` in Wave 5b so HUD tests in
 * `plugin/core` can reuse the same fixture without duplicating it. The
 * `sendActionBar` / `sendTitle` recording surface is added in the same
 * wave together with the matching `IMcPlayer` interface methods (Worker δ).
 */
public class FakeMcServer : IMcServer {
    private val byId: MutableMap<PlayerId, IMcPlayer> = mutableMapOf()

    public fun register(player: IMcPlayer) {
        byId[player.id] = player
    }

    override fun findPlayer(id: PlayerId): IMcPlayer? = byId[id]

    override fun findPlayerByName(name: String): IMcPlayer? =
        byId.values.firstOrNull { it.name == name }
}

/**
 * In-memory [IMcPlayer]. Records `sendMessage` / `giveItem` invocations so
 * tests can assert on what the use case did. HUD-related recording
 * (`sendActionBar` / `sendTitle`) is filled in by Wave 5b Worker δ.
 */
public class FakeMcPlayer(
    override val id: PlayerId,
    override val name: String = "fake-player",
    override val location: Vec3 = Vec3(0.0, 64.0, 0.0),
    override val locale: String? = "en",
    public var heldMaterialKey: String? = null,
) : IMcPlayer {

    public data class GiveItemCall(
        val template: IMcItemTemplate,
        val displayNameOverride: String?,
        val loreOverride: List<String>,
    )

    public data class ActionBarCall(
        val key: MessageKey,
        val placeholders: Map<String, String>,
    )

    public data class TitleCall(
        val titleKey: MessageKey,
        val subtitleKey: MessageKey?,
        val fadeIn: Duration,
        val stay: Duration,
        val fadeOut: Duration,
    )

    public val messages: MutableList<String> = mutableListOf()
    public val given: MutableList<GiveItemCall> = mutableListOf()
    public val actionBars: MutableList<ActionBarCall> = mutableListOf()
    public val titles: MutableList<TitleCall> = mutableListOf()

    override fun sendMessage(rendered: String) {
        messages += rendered
    }

    override fun giveItem(template: IMcItemTemplate, displayNameOverride: String?, loreOverride: List<String>) {
        given += GiveItemCall(template, displayNameOverride, loreOverride)
    }

    override fun heldItemMaterialKey(): String? = heldMaterialKey

    override fun sendActionBar(messageKey: MessageKey, placeholders: Map<String, String>) {
        actionBars += ActionBarCall(messageKey, placeholders)
    }

    override fun sendTitle(
        titleKey: MessageKey,
        subtitleKey: MessageKey?,
        fadeIn: Duration,
        stay: Duration,
        fadeOut: Duration,
    ) {
        titles += TitleCall(titleKey, subtitleKey, fadeIn, stay, fadeOut)
    }
}
