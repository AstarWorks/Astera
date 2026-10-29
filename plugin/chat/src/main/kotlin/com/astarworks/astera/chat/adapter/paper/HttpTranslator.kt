package com.astarworks.astera.chat.adapter.paper

import com.astarworks.astera.chat.application.config.TranslationConfig
import com.astarworks.astera.chat.application.port.outbound.ITranslator
import com.astarworks.astera.chat.application.port.outbound.TranslateError
import com.astarworks.astera.core.domain.Result
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Model-specific protocol stays at this boundary. No SDK or inference runs in Minecraft. */
public class HttpTranslator(
    private val http: HttpClient,
    baseUrl: String,
    private val config: TranslationConfig,
) : ITranslator {
    private val profile = config.modelProfile
    private val timeoutMs = config.timeoutMs
    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/v1/chat/completions")
    private data class Key(val text: String, val from: String, val to: String)
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<Key, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>): Boolean = size > 512
    })

    init {
        require(endpoint.scheme in setOf("http", "https") && endpoint.host != null)
        require(profile in setOf("translategemma", "hy-mt"))
    }

    override fun translate(text: String, from: String, to: String): CompletionStage<Result<String, TranslateError>> {
        if (from !in languageNames || to !in languageNames)
            return CompletableFuture.completedFuture(Result.Failure(TranslateError.UnsupportedLanguage))
        val key = Key(text, from, to)
        cache[key]?.let { return CompletableFuture.completedFuture(Result.Success(it)) }
        return try {
            val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(text, from, to).toString())).build()
            http.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 65536))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .handle<Result<String, TranslateError>> { response, failure ->
                    if (failure != null) {
                        val cause = failure.cause ?: failure
                        Result.Failure(if (cause is HttpTimeoutException || cause is java.util.concurrent.TimeoutException)
                            TranslateError.Timeout else TranslateError.Unavailable)
                    } else if (response.statusCode() != 200) Result.Failure(TranslateError.Unavailable)
                    else runCatching {
                        val root = Json.parseToJsonElement(response.body()).jsonObject
                        val choice = root.getValue("choices").jsonArray.first().jsonObject
                        require(choice["finish_reason"]?.jsonPrimitive?.content == "stop")
                        val translated = choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.content.trim()
                        require(translated.isNotBlank() && translated.length <= 4000)
                        cache[key] = translated
                        Result.Success(translated)
                    }.getOrElse { Result.Failure(TranslateError.InvalidResponse) }
                }
        } catch (_: RuntimeException) { CompletableFuture.completedFuture(Result.Failure(TranslateError.Unavailable)) }
    }

    private fun payload(text: String, from: String, to: String): JsonObject = buildJsonObject {
        put("model", profile)
        put("temperature", 0)
        put("max_tokens", 512)
        put("stream", false)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                var prompt = config.prompts.getValue(profile)
                // Substitute text last: player text is never interpreted as a template.
                for ((key, value) in mapOf("source" to languageNames.getValue(from), "target" to languageNames.getValue(to),
                    "from" to from, "to" to to, "context" to config.context,
                    "examples" to config.examples["$from-$to"].orEmpty(), "text" to text)) {
                    prompt = prompt.replace("{$key}", value)
                }
                put("content", prompt)
            }
        }

    }

    private companion object {
        val languageNames = mapOf("en" to "English", "ja" to "Japanese", "ko" to "Korean", "es" to "Spanish")
    }
}
