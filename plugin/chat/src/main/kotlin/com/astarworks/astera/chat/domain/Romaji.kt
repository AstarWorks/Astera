package com.astarworks.astera.chat.domain

/** Independent table-driven romanisation; no LunaChat code or tables are used. */
public object Romaji {
    private val syllables: Map<String, String> = buildMap {
        val rows = listOf(
            "a i u e o" to "あいうえお", "ka ki ku ke ko" to "かきくけこ",
            "sa si su se so" to "さしすせそ", "ta ti tu te to" to "たちつてと",
            "na ni nu ne no" to "なにぬねの", "ha hi hu he ho" to "はひふへほ",
            "ma mi mu me mo" to "まみむめも", "ra ri ru re ro" to "らりるれろ",
            "ga gi gu ge go" to "がぎぐげご", "za zi zu ze zo" to "ざじずぜぞ",
            "da di du de do" to "だぢづでど", "ba bi bu be bo" to "ばびぶべぼ",
            "pa pi pu pe po" to "ぱぴぷぺぽ", "xa xi xu xe xo" to "ぁぃぅぇぉ",
            "la li lu le lo" to "ぁぃぅぇぉ",
        )
        rows.forEach { (keys, kana) -> keys.split(' ').zip(kana.toList()).forEach { (k, v) -> put(k, v.toString()) } }
        listOf("k" to "き", "s" to "し", "t" to "ち", "n" to "に", "h" to "ひ", "m" to "み",
            "r" to "り", "g" to "ぎ", "z" to "じ", "d" to "ぢ", "b" to "び", "p" to "ぴ").forEach { (c, k) ->
            listOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ").forEach { (v, y) -> put(c + "y" + v, k + y) }
        }
        putAll(mapOf("shi" to "し", "chi" to "ち", "tsu" to "つ", "fu" to "ふ", "ji" to "じ",
            "ya" to "や", "yu" to "ゆ", "yo" to "よ", "wa" to "わ", "wo" to "を", "wi" to "うぃ", "we" to "うぇ",
            "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ", "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
            "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ", "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
            "xtsu" to "っ", "ltsu" to "っ", "xtu" to "っ", "ltu" to "っ", "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
            "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ", "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ", "-" to "ー"))
    }
    private val url = Regex("(?i)(https?://|www\\.|\\S+\\.[a-z]{2,}(/\\S*)?)")

    /** Null means the input is deliberately bypassed or contains no convertible syllables. */
    public fun hiragana(message: String): String? {
        if (message.any { it.code > 127 } || message.trimStart().startsWith('#') ||
            message.trimStart().startsWith('/') || url.containsMatchIn(message)) return null
        val text = message.lowercase()
        var i = 0
        var changed = false
        val result = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '&' && next != null && next in "0123456789abcdefklmnor" -> {
                    result.append(message, i, i + 2); i += 2
                }
                c == 'n' && next == '\'' -> { result.append('ん'); i += 2; changed = true }
                c == 'n' && next == 'n' -> {
                    result.append('ん'); changed = true
                    i += if (text.getOrNull(i + 2) in "aiueoy".toList()) 1 else 2
                }
                c == 'n' && (next == null || next !in "aiueoy") -> { result.append('ん'); i++; changed = true }
                c in "bcdfghjklmpqrstvwxyz" && (c == next || (c == 't' && text.startsWith("ch", i + 1))) -> {
                    result.append('っ'); i++; changed = true
                }
                else -> {
                    val key = (4 downTo 1).mapNotNull { len -> text.substring(i, (i + len).coerceAtMost(text.length)).takeIf { it in syllables } }.firstOrNull()
                    if (key != null) { result.append(syllables.getValue(key)); i += key.length; changed = true }
                    else { result.append(message[i]); i++ }
                }
            }
        }
        return result.toString().takeIf { changed }
    }
}
