package com.astarworks.astera.chat.adapter.paper

import com.astarworks.astera.chat.application.port.outbound.IKanjiConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Only kana is sent; name, UUID and the original romanised text never leave the server. */
public class GoogleKanjiConverter(private val http: HttpClient) : IKanjiConverter {
    override fun convert(kana: String): CompletionStage<String> {
        val encoded = URLEncoder.encode(kana, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text=$encoded"))
            .timeout(Duration.ofSeconds(2)).GET().build()
        return try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).orTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply { response ->
                    if (response.statusCode() != 200 || response.body().length > 65536) kana
                    else runCatching {
                        val segments = Json.parseToJsonElement(response.body()).jsonArray
                        require(segments.isNotEmpty())
                        segments.joinToString("") { it.jsonArray[1].jsonArray.first().jsonPrimitive.content }
                            .takeIf { it.isNotBlank() } ?: kana
                    }.getOrDefault(kana)
                }.exceptionally { kana }
        } catch (_: RuntimeException) { CompletableFuture.completedFuture(kana) }
    }
}
