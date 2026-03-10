package com.voicebot.data.normalizer

import android.content.Context
import android.util.Log
import com.voicebot.domain.port.TextNormalizer
import org.json.JSONObject

// ──────────────────────────────────────────────────────────────────────────────
// NumberTextNormalizer — converts digit sequences to Vietnamese spoken form
// ──────────────────────────────────────────────────────────────────────────────

class NumberTextNormalizer : TextNormalizer {

    private val digits = arrayOf("không","một","hai","ba","bốn","năm","sáu","bảy","tám","chín")

    override fun normalize(text: String): String =
        Regex("\\d+").replace(text) { m ->
            val s = m.value
            try {
                if (s.length > 9) s.map { digits[it.toString().toInt()] }.joinToString(" ")
                else readNumber(s.toLong())
            } catch (_: Exception) { s }
        }

    private fun readNumber(n: Long): String {
        if (n == 0L) return digits[0]
        var rem = n; val sb = StringBuilder()
        listOf(1_000_000_000L to "tỷ", 1_000_000L to "triệu", 1_000L to "nghìn").forEach { (div, unit) ->
            val q = rem / div
            if (q > 0) { sb.append(readTriple(q.toInt())).append(" $unit "); rem %= div }
        }
        if (rem > 0) sb.append(readTriple(rem.toInt()))
        return sb.toString().trim()
    }

    private fun readTriple(num: Int): String {
        val h = num / 100; val t = (num % 100) / 10; val u = num % 10
        val sb = StringBuilder()
        if (h > 0) { sb.append(digits[h]).append(" trăm "); if (t == 0 && u > 0) sb.append("linh ") }
        when {
            t > 1 -> { sb.append(digits[t]).append(" mươi ")
                if (u == 1) sb.append("mốt ") else if (u == 5) sb.append("lăm ") else if (u > 0) sb.append(digits[u]) }
            t == 1 -> { sb.append("mười ")
                if (u == 1) sb.append("một ") else if (u == 5) sb.append("lăm ") else if (u > 0) sb.append(digits[u]) }
            t == 0 && u > 0 -> sb.append(digits[u])
        }
        return sb.toString()
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// ProductTextNormalizer — replaces brand/product abbreviations using JSON dict
// ──────────────────────────────────────────────────────────────────────────────

class ProductTextNormalizer(
    context: Context,
    private val assetFile: String = "misa_product.json"
) : TextNormalizer {

    companion object { private const val TAG = "ProductNormalizer" }

    private val replacements: List<Pair<String, String>>

    init {
        val merged = mutableMapOf<String, String>()
        try {
            val json = JSONObject(context.assets.open(assetFile).bufferedReader().readText())
            json.keys().forEach { cat ->
                val obj = json.getJSONObject(cat)
                obj.keys().forEach { key ->
                    val lk = key.lowercase().trim()
                    if (!merged.containsKey(lk)) merged[lk] = obj.getString(key)
                }
            }
            Log.i(TAG, "Loaded ${merged.size} product replacements from $assetFile")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $assetFile", e)
        }
        // Longest match first to avoid partial replacement errors
        replacements = merged.entries.sortedByDescending { it.key.length }.map { it.key to it.value }
    }

    override fun normalize(text: String): String {
        var result = text.lowercase()
        for ((key, value) in replacements) {
            result = Regex("\\b${Regex.escape(key)}\\b", RegexOption.IGNORE_CASE).replace(result, value)
        }
        return result
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// CompositeTextNormalizer — chains multiple normalizers in sequence
// ──────────────────────────────────────────────────────────────────────────────

class CompositeTextNormalizer(private val normalizers: List<TextNormalizer>) : TextNormalizer {
    override fun normalize(text: String): String =
        normalizers.fold(text) { acc, n -> n.normalize(acc) }
}
