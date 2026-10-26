package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.usecase.settings.SetLocaleUseCase
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalePreferenceScenarioTest {
    @Test
    fun `saved language changes rendering immediately with a persistence overlay`() {
        scenario("saved-locale-rendering", persistenceOverlay = true) {
            val viewer = player("viewer")
            val settings = sim.services.getOrThrow<IPlayerSettingsRepository>()
            val messages = sim.services.getOrThrow<IMessageRenderer>()
            val label = LocalizedText(mapOf(LocaleCode.EN to "Hello", LocaleCode.JA to "こんにちは"))

            assertThat(SetLocaleUseCase(settings).set(viewer.id, LocaleCode.JA).isSuccess).isTrue()
            assertThat(messages.renderText(viewer.id, label)).isEqualTo("こんにちは")
            assertThat(SetLocaleUseCase(settings).set(viewer.id, LocaleCode.EN).isSuccess).isTrue()
            assertThat(messages.renderText(viewer.id, label)).isEqualTo("Hello")
        }
    }

    @Test
    fun `client language is ignored and an explicit choice beats the server default`() {
        scenario("client-locale-ignored", persistenceOverlay = true) {
            // The sim's server default is en (ADR-0283); a ja client must not change it.
            val viewer = player("viewer", clientLocale = "ja_jp")
            val settings = sim.services.getOrThrow<IPlayerSettingsRepository>()
            val messages = sim.services.getOrThrow<IMessageRenderer>()
            val label = LocalizedText(mapOf(LocaleCode.EN to "Hello", LocaleCode.JA to "こんにちは"))

            assertThat(settings.findOrDefault(viewer.id).locale).isNull()
            assertThat(messages.renderText(viewer.id, label)).isEqualTo("Hello")
            assertThat(messages.renderText(null, label)).isEqualTo("Hello")
            assertThat(SetLocaleUseCase(settings).set(viewer.id, LocaleCode.JA).isSuccess).isTrue()
            assertThat(messages.renderText(viewer.id, label)).isEqualTo("こんにちは")
        }
    }
}
