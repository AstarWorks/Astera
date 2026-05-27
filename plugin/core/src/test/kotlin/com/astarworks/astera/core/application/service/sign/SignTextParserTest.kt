package com.astarworks.astera.core.application.service.sign

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.sign.SignAction
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins [SignTextParser] grammar (Wave 5block-embed, ADR-0120).
 */
internal class SignTextParserTest {

    @Test
    fun `non-magic header returns null`() {
        assertThat(SignTextParser.parse(listOf("plain", "teleport", "world", "0,64,0"))).isNull()
        assertThat(SignTextParser.parse(listOf("", "", "", ""))).isNull()
        assertThat(SignTextParser.parse(emptyList())).isNull()
    }

    @Test
    fun `magic header is case-insensitive and trim-tolerant`() {
        val a = SignTextParser.parse(listOf("[ASTERA]", "display", "astera.test", ""))
        val b = SignTextParser.parse(listOf("  [astera]  ", "display", "astera.test", ""))
        val c = SignTextParser.parse(listOf("[Astera]", "display", "astera.test", ""))

        assertThat(a).isEqualTo(SignAction.Display(MessageKey("astera.test")))
        assertThat(b).isEqualTo(a)
        assertThat(c).isEqualTo(a)
    }

    @Test
    fun `teleport parses world plus xyz`() {
        val r = SignTextParser.parse(listOf("[ASTERA]", "teleport", "lobby", "10.5, 64, -3"))
        assertThat(r).isEqualTo(SignAction.TeleportTo(WorldId("lobby"), Vec3(10.5, 64.0, -3.0)))
    }

    @Test
    fun `teleport rejects malformed coordinates`() {
        assertThat(SignTextParser.parse(listOf("[ASTERA]", "teleport", "lobby", "abc"))).isNull()
        assertThat(SignTextParser.parse(listOf("[ASTERA]", "teleport", "lobby", "0,64"))).isNull()
        assertThat(SignTextParser.parse(listOf("[ASTERA]", "teleport", "lobby", "0,a,0"))).isNull()
    }

    @Test
    fun `teleport rejects blank world`() {
        assertThat(SignTextParser.parse(listOf("[ASTERA]", "teleport", "  ", "0,64,0"))).isNull()
    }

    @Test
    fun `display parses an i18n key`() {
        val r = SignTextParser.parse(listOf("[ASTERA]", "display", "astera.sign.welcome", ""))
        assertThat(r).isEqualTo(SignAction.Display(MessageKey("astera.sign.welcome")))
    }

    @Test
    fun `display rejects malformed key`() {
        assertThat(SignTextParser.parse(listOf("[ASTERA]", "display", "UPPER.bad", ""))).isNull()
        assertThat(SignTextParser.parse(listOf("[ASTERA]", "display", "  ", ""))).isNull()
    }

    @Test
    fun `unknown action type returns null`() {
        assertThat(SignTextParser.parse(listOf("[ASTERA]", "explode", "lobby", "0,64,0"))).isNull()
    }
}
