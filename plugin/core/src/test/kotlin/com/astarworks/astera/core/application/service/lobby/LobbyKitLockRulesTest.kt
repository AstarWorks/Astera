package com.astarworks.astera.core.application.service.lobby

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The exploit-proofing decisions of the locked lobby kit (ADR-0185). */
class LobbyKitLockRulesTest {

    @Test
    fun `an unrelated click is left alone`() {
        assertThat(LobbyKitLockRules.cancelClick(KitClickFacts())).isFalse()
        assertThat(LobbyKitLockRules.cancelClick(KitClickFacts(topIsOwnCrafting = true))).isFalse()
    }

    @Test
    fun `any click that picks up, holds or swaps a kit item is cancelled`() {
        assertThat(LobbyKitLockRules.cancelClick(KitClickFacts(clickedIsKit = true))).isTrue()
        assertThat(LobbyKitLockRules.cancelClick(KitClickFacts(cursorIsKit = true))).isTrue()
        assertThat(LobbyKitLockRules.cancelClick(KitClickFacts(swapSourceIsKit = true))).isTrue() // number key / F
    }

    @Test
    fun `while buttons are shown the crafting zone is read-only even for ordinary items`() {
        val shown = KitClickFacts(inCraftingZone = true, buttonsShown = true, topIsOwnCrafting = true)
        assertThat(LobbyKitLockRules.cancelClick(shown)).isTrue()
        assertThat(LobbyKitLockRules.cancelClick(shown.copy(buttonsShown = false))).isFalse()
    }

    @Test
    fun `double-click gather is blocked on the own crafting screen while buttons are shown`() {
        val gather = KitClickFacts(collectToCursor = true, buttonsShown = true, topIsOwnCrafting = true)
        assertThat(LobbyKitLockRules.cancelClick(gather)).isTrue()
        assertThat(LobbyKitLockRules.cancelClick(gather.copy(topIsOwnCrafting = false))).isFalse()
    }

    @Test
    fun `only a primary click on a kit item in the crafting zone presses a button`() {
        val on = KitClickFacts(clickedIsKit = true, inCraftingZone = true, topIsOwnCrafting = true, buttonsShown = true)
        assertThat(LobbyKitLockRules.pressesButton(on, primaryClick = true)).isTrue()
        assertThat(LobbyKitLockRules.pressesButton(on, primaryClick = false)).isFalse() // middle click / drop key
        assertThat(LobbyKitLockRules.pressesButton(on.copy(inCraftingZone = false), primaryClick = true)).isFalse() // hotbar copy
        assertThat(LobbyKitLockRules.pressesButton(on.copy(clickedIsKit = false), primaryClick = true)).isFalse()
    }

    @Test
    fun `drags are cancelled when they carry, cover or land on kit items`() {
        assertThat(LobbyKitLockRules.cancelDrag(false, false, false, false)).isFalse()
        assertThat(LobbyKitLockRules.cancelDrag(true, false, false, false)).isTrue()
        assertThat(LobbyKitLockRules.cancelDrag(false, false, false, true)).isTrue()
        assertThat(LobbyKitLockRules.cancelDrag(false, true, true, false)).isTrue()
        assertThat(LobbyKitLockRules.cancelDrag(false, false, true, false)).isFalse()
    }
}
