package com.astarworks.astera.chat.application

import com.astarworks.astera.chat.application.config.ChatFrame
import com.astarworks.astera.chat.application.config.TranslationConfig
import com.astarworks.astera.chat.application.port.outbound.ITranslator
import com.astarworks.astera.core.domain.Result
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/** Sender-side fan-out only: receiving backends never invoke the translator. */
public class ChatTranslation(
    private val config: TranslationConfig,
    private val translator: ITranslator?,
    private val warn: (String) -> Unit,
) {
    public fun prepare(frame: ChatFrame, text: String, names: Collection<String>): CompletionStage<ChatFrame> {
        val protected = mutableListOf<String>()
        // Mask URLs before stripping codes: query strings may contain literal &b, &c, etc.
        fun mask(match: MatchResult): String {
            protected += match.value
            return "__ASTERA_${protected.lastIndex}__"
        }
        val urls = Regex("(?i)https?://\\S+|www\\.\\S+|\\b[\\w-]+\\.[a-z]{2,}(?:/\\S*)?")
        var plain = decoration.replace(urls.replace(text, ::mask), "")
        val namePattern = names.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        if (namePattern.isNotEmpty()) {
            plain = Regex("(?<![A-Za-z0-9_])(?:$namePattern)(?![A-Za-z0-9_])").replace(plain, ::mask)
        }
        val source = detect(plain)
        val original = frame.copy(sourceLanguage = source)
        if (!config.enabled || translator == null || plain.isBlank()) return CompletableFuture.completedFuture(original)
        val requests = config.languages.filter { it != source }.associateWith { target ->
            translator.translate(plain, source, target).toCompletableFuture()
        }
        val all = CompletableFuture.allOf(*requests.values.toTypedArray())
        return all.completeOnTimeout(null, config.timeoutMs, TimeUnit.MILLISECONDS).handle { _, failure ->
            // A single total budget, even with four target languages or an unresponsive adapter.
            if (failure != null || requests.values.any { !it.isDone || it.isCompletedExceptionally }) {
                warn("Chat translation unavailable or timed out; delivering original")
                requests.values.forEach { it.cancel(true) }
                original
            } else {
                var failed = false
                val translations = requests.mapNotNull { (lang, request) ->
                    val outcome = request.join()
                    if (outcome is Result.Success) {
                        var translated = decoration.replace(outcome.value, "").trim()
                        val intact = protected.indices.all { i ->
                            Regex(Regex.escape("__ASTERA_${i}__")).findAll(translated).count() == 1
                        }
                        if (!intact || translated.isBlank() || translated.length > 4000) {
                            failed = true
                            null
                        } else {
                            protected.forEachIndexed { i, token -> translated = translated.replace("__ASTERA_${i}__", token) }
                            lang to translated
                        }
                    } else { failed = true; null }
                }.toMap()
                if (failed) warn("Chat translation failed; delivering original for unavailable languages")
                original.copy(translations = translations)
            }
        }
    }

    public companion object {
        public val decoration: Regex = Regex("(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]|§[0-9a-fk-orx]")
        // ponytail: ambiguous Latin defaults to English; expand this word list after chat benchmarks.
        public fun detect(text: String): String = when {
            text.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' } -> "ja"
            text.any { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' || it in '\u3130'..'\u318f' } -> "ko"
            Regex("(?i)[¿¡ñáéíóúü]|\\b(hola|gracias|ayuda|vamos|necesito|donde|dónde|por favor|enemigo)\\b").containsMatchIn(text) -> "es"
            else -> "en"
        }
    }
}

/** Shared by the Paper display and simulator: exact locale match, otherwise original. */
public object ChatView {
    public data class Message(val text: String, val marker: String? = null, val hover: String? = null)

    public fun message(frame: ChatFrame, locale: String, enabled: Boolean, marker: String): Message {
        val language = locale.trim().substringBefore('_').substringBefore('-').lowercase()
        val translated = if (enabled && language != frame.sourceLanguage) frame.translations[language] else null
        return if (translated == null) Message(frame.message)
            else Message(translated, marker, "[${frame.sourceLanguage}] ${frame.message}")
    }
}
