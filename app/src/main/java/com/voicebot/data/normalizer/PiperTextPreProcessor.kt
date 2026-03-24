package com.voicebot.data.normalizer

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Pre-processor chuyên biệt cho Piper TTS.
 *
 * Pipeline (theo thứ tự):
 *   1. cleanSymbols()      — lọc/đổi ký tự đặc biệt
 *   2. normalizeNumbers()  — số → chữ (phone, date, thường)
 *   3. applyMisaProducts() — map tên sản phẩm + thêm gạch ngang
 *
 * Chỉ được gọi bên trong PiperTtsEngine.speak() — KHÔNG dùng cho màn hình UI.
 */
class PiperTextPreProcessor(context: Context) {

    companion object {
        private const val TAG = "PiperPreProcessor"
        private const val ASSET_FILE = "misa_product.json"

        private val DIGITS = arrayOf(
            "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
        )

        // Regex patterns — compiled once
        private val PHONE_PATTERN  = Regex("""(?<!\d)0\d{9}(?!\d)""")
        private val DATE_FULL_PATTERN = Regex("""(?<!\d)(\d{1,2})/(\d{1,2})/(\d{4})(?!\d)""")
        private val DATE_SHORT_PATTERN = Regex("""(?<!\d)(\d{1,2})/(\d{1,2})(?!/\d)(?!\d)""")
        private val NUMBER_PATTERN = Regex("""\d+""")

        private val ELLIPSIS_PATTERN  = Regex("""[…]|\.\.\.|[?!]+""")
        // Dấu xuống dòng (có thể kèm khoảng trắng/tab) → dấu chấm
        private val NEWLINE_PATTERN   = Regex("""[ \t]*[\r\n]+[ \t]*""")
        // Dấu bullet "-" ở đầu dòng (sau khi newline đã thành ".") → bỏ
        private val BULLET_PATTERN    = Regex("""(?<=\.)\s*-\s*""")
        // ":" → dấu chấm (kết thúc câu dẫn, ví dụ: "sản phẩm là:")
        private val COLON_PATTERN     = Regex(""":""")
        private val SEPARATOR_PATTERN = Regex("""[;/\\|]""")
        private val UNWANTED_PATTERN  = Regex("""[^\p{L}\p{N}\s,.\-]""")
        private val MULTI_COMMA  = Regex(""",+""")
        private val MULTI_DOT    = Regex("""\.+""")
        private val MULTI_SPACE  = Regex("""\s+""")
    }

    // ── MISA product replacements (value với gạch ngang) ─────────────────
    private val productReplacements: List<Pair<String, String>>

    init {
        val merged = mutableMapOf<String, String>()
        try {
            val json = JSONObject(context.assets.open(ASSET_FILE).bufferedReader().readText())

            // Pass 1: load raw values (chưa hyphenate) cho tất cả các key đơn và key nhiều từ
            val rawMap = mutableMapOf<String, String>()
            json.keys().forEach { cat ->
                val obj = json.getJSONObject(cat)
                obj.keys().forEach { key ->
                    val lk = key.lowercase().trim()
                    if (!rawMap.containsKey(lk)) rawMap[lk] = obj.getString(key).trim()
                }
            }

            // Pass 2: build giá trị có gạch ngang, tra từng từ con trong rawMap
            // để biết chính xác số âm tiết phiên âm của từng từ gốc.
            //
            // Ví dụ: key="misa amis vps", rawMap["misa"]="mi sa" (2), rawMap["amis"]="a mít" (2),
            //        rawMap["vps"]="văn phòng số" (3)
            //   → "mi-sa a-mít văn-phòng-số"
            //
            // Nếu từ con không có trong rawMap (không biết số âm tiết),
            // fallback: chia đều phần còn lại.
            rawMap.forEach { (lk, raw) ->
                val keyWords = lk.split(" ").filter { it.isNotEmpty() }
                val valueTokens = raw.split(" ").filter { it.isNotEmpty() }
                val hyphenated = if (keyWords.size <= 1) {
                    // Từ đơn → nối tất cả âm tiết bằng gạch ngang
                    valueTokens.joinToString("-")
                } else {
                    // Từ ghép → tra từng từ con để biết số âm tiết tương ứng
                    val groups = mutableListOf<String>()
                    var idx = 0
                    for ((i, word) in keyWords.withIndex()) {
                        val syllableCount = when {
                            rawMap.containsKey(word) -> {
                                // Tra số âm tiết từ từ đơn tương ứng
                                rawMap[word]!!.split(" ").filter { it.isNotEmpty() }.size
                            }
                            else -> {
                                // Không có trong map: chia đều phần còn lại
                                val remaining = valueTokens.size - idx
                                val wordsLeft = keyWords.size - i
                                remaining / wordsLeft
                            }
                        }
                        val end = (idx + syllableCount).coerceAtMost(valueTokens.size)
                        groups.add(valueTokens.subList(idx, end).joinToString("-"))
                        idx = end
                    }
                    // Nếu còn dư âm tiết (do làm tròn), gắn vào nhóm cuối
                    if (idx < valueTokens.size) {
                        val last = groups.removeLast()
                        groups.add(last + "-" + valueTokens.subList(idx, valueTokens.size).joinToString("-"))
                    }
                    groups.joinToString(" ")
                }
                merged[lk] = hyphenated
            }
            Log.i(TAG, "Loaded ${merged.size} product replacements (hyphenated)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET_FILE", e)
        }
        // Longest key first — tránh partial match
        productReplacements = merged.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }
    }

    // ── Public entry point ────────────────────────────────────────────────

    fun process(text: String): String {
        var s = text
        s = cleanSymbols(s)
        s = normalizeNumbers(s)
        s = applyMisaProducts(s)
        return s
    }

    // ── Step 1: Clean symbols ─────────────────────────────────────────────

    private fun cleanSymbols(text: String): String {
        var s = text
        // ? ! … ... → dấu chấm
        s = ELLIPSIS_PATTERN.replace(s, ".")
        // Xuống dòng → dấu chấm (tạo điểm dừng câu cho TTS)
        s = NEWLINE_PATTERN.replace(s, ". ")
        // Dấu bullet "-" ngay sau dấu chấm (do newline vừa chuyển) → bỏ
        s = BULLET_PATTERN.replace(s, " ")
        // ":" → dấu chấm (kết thúc câu dẫn, vd: "sản phẩm là:")
        s = COLON_PATTERN.replace(s, ".")
        // ; / \ | → dấu phẩy
        s = SEPARATOR_PATTERN.replace(s, ",")
        // Xóa mọi ký tự không phải chữ, số, khoảng trắng, phẩy, chấm, gạch ngang
        s = UNWANTED_PATTERN.replace(s, " ")
        // Collapse dấu phẩy/chấm liên tiếp
        s = MULTI_COMMA.replace(s, ",")
        s = MULTI_DOT.replace(s, ".")
        // Collapse khoảng trắng
        s = MULTI_SPACE.replace(s, " ").trim()
        return s
    }

    // ── Step 2: Normalize numbers ─────────────────────────────────────────

    /**
     * Thứ tự xử lý:
     *   1. Số điện thoại: 10 chữ số bắt đầu 0
     *   2. Ngày tháng năm: DD/MM/YYYY hoặc DD/MM
     *   3. Số thường: đọc số nguyên bình thường
     *
     * Dùng placeholder để tránh double-replace.
     */
    private fun normalizeNumbers(text: String): String {
        // Dùng map để track các vùng đã replace (tránh replace chồng lên nhau)
        // Approach đơn giản: thay thế tuần tự nhưng đảm bảo order ưu tiên
        // bằng cách xử lý từng pattern trên text với placeholder
        val parts = mutableListOf<String>() // lưu từng segment đã được xử lý
        var remaining = text

        // 1. Số điện thoại (10 chữ số bắt đầu 0) — xử lý trước
        remaining = PHONE_PATTERN.replace(remaining) { m ->
            readPhoneNumber(m.value)
        }

        // 2. Ngày tháng năm DD/MM/YYYY — xử lý trước DD/MM
        remaining = DATE_FULL_PATTERN.replace(remaining) { m ->
            val d = m.groupValues[1].toIntOrNull() ?: 0
            val mo = m.groupValues[2].toIntOrNull() ?: 0
            val y = m.groupValues[3].toLongOrNull() ?: 0L
            "ngày ${readNumber(d.toLong())} tháng ${readNumber(mo.toLong())} năm ${readNumber(y)}"
        }

        // 3. Ngày tháng DD/MM (không có năm)
        remaining = DATE_SHORT_PATTERN.replace(remaining) { m ->
            val d = m.groupValues[1].toIntOrNull() ?: 0
            val mo = m.groupValues[2].toIntOrNull() ?: 0
            "ngày ${readNumber(d.toLong())} tháng ${readNumber(mo.toLong())}"
        }

        // 4. Số thường còn lại
        remaining = NUMBER_PATTERN.replace(remaining) { m ->
            val s = m.value
            try {
                if (s.length > 9) s.map { DIGITS[it.toString().toInt()] }.joinToString(" ")
                else readNumber(s.toLong())
            } catch (_: Exception) { s }
        }

        return remaining
    }

    /** Đọc số điện thoại 10 chữ số theo từng cặp: 0901234567 → "không chín-không một-..." */
    private fun readPhoneNumber(phone: String): String {
        // Tách: chữ số đầu + 3 cặp còn lại: 0 9 01 23 45 67
        // Convention Việt Nam: đọc theo nhóm 4-3-3 hoặc từng cặp
        // → đọc từng cặp cho rõ ràng
        return phone.chunked(2).joinToString(" ") { pair ->
            pair.map { DIGITS[it.toString().toInt()] }.joinToString(" ")
        }
    }

    // ── Step 3: Apply MISA product names ─────────────────────────────────

    private fun applyMisaProducts(text: String): String {
        var result = text.lowercase()
        for ((key, value) in productReplacements) {
            result = Regex(
                "\\b${Regex.escape(key)}\\b",
                RegexOption.IGNORE_CASE
            ).replace(result, value)
        }
        return result
    }

    // ── Number reading helpers (same logic as NumberTextNormalizer) ───────

    private fun readNumber(n: Long): String {
        if (n == 0L) return DIGITS[0]
        var rem = n
        val sb = StringBuilder()
        listOf(
            1_000_000_000L to "tỷ",
            1_000_000L     to "triệu",
            1_000L         to "nghìn"
        ).forEach { (div, unit) ->
            val q = rem / div
            if (q > 0) {
                sb.append(readTriple(q.toInt())).append(" $unit ")
                rem %= div
            }
        }
        if (rem > 0) sb.append(readTriple(rem.toInt()))
        return sb.toString().trim()
    }

    private fun readTriple(num: Int): String {
        val h = num / 100
        val t = (num % 100) / 10
        val u = num % 10
        val sb = StringBuilder()
        if (h > 0) {
            sb.append(DIGITS[h]).append(" trăm ")
            if (t == 0 && u > 0) sb.append("linh ")
        }
        when {
            t > 1 -> {
                sb.append(DIGITS[t]).append(" mươi ")
                when {
                    u == 1 -> sb.append("mốt ")
                    u == 5 -> sb.append("lăm ")
                    u > 0  -> sb.append(DIGITS[u]).append(" ")
                }
            }
            t == 1 -> {
                sb.append("mười ")
                when {
                    u == 1 -> sb.append("một ")
                    u == 5 -> sb.append("lăm ")
                    u > 0  -> sb.append(DIGITS[u]).append(" ")
                }
            }
            t == 0 && u > 0 -> sb.append(DIGITS[u]).append(" ")
        }
        return sb.toString().trimEnd()
    }
}
