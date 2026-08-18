package com.squarehub.android

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context

// Lưu lại lịch sử mỗi lần app tự động kiểm tra kênh Telegram trong nền (mỗi
// lần "quét"): quét lúc nào, kiểm tra bao nhiêu kênh, và với mỗi bài tìm thấy
// thì đăng lên Square có thành công không (kèm vài chữ đầu, không lưu nguyên
// văn) - để người dùng tự xem lại được tính năng có thật sự chạy hay không,
// không phải đoán mò.
object TelegramScanLog {
    private const val PREFS_NAME = "square_hub_telegram_log"
    private const val KEY_SCANS = "scans"

    // Quét mỗi 15 phút nên giữ khoảng vài trăm lần gần nhất là đủ dùng (vài
    // ngày gần nhất), tránh SharedPreferences phình to mãi theo thời gian.
    private const val MAX_SCANS = 200

    data class PostEntry(
        val channelUsername: String,
        val success: Boolean,
        val snippet: String,
        val message: String
    )

    data class ScanEntry(
        val timestampMs: Long,
        val channelsChecked: Int,
        val posts: List<PostEntry>
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordScan(context: Context, channelsChecked: Int, posts: List<PostEntry>) {
        val arr = try {
            JSONArray(prefs(context).getString(KEY_SCANS, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }

        val postsArr = JSONArray()
        for (p in posts) {
            postsArr.put(
                JSONObject().apply {
                    put("channelUsername", p.channelUsername)
                    put("success", p.success)
                    put("snippet", p.snippet)
                    put("message", p.message)
                }
            )
        }

        arr.put(
            JSONObject().apply {
                put("timestampMs", System.currentTimeMillis())
                put("channelsChecked", channelsChecked)
                put("posts", postsArr)
            }
        )

        val trimmed = if (arr.length() > MAX_SCANS) {
            val start = arr.length() - MAX_SCANS
            JSONArray().apply {
                for (i in start until arr.length()) put(arr.get(i))
            }
        } else {
            arr
        }

        prefs(context).edit().putString(KEY_SCANS, trimmed.toString()).apply()
    }

    // Trả về danh sách các lần quét, MỚI NHẤT TRƯỚC.
    fun getScans(context: Context): List<ScanEntry> {
        val raw = prefs(context).getString(KEY_SCANS, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }

        val result = mutableListOf<ScanEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val postsArr = obj.optJSONArray("posts") ?: JSONArray()
            val posts = mutableListOf<PostEntry>()
            for (j in 0 until postsArr.length()) {
                val p = postsArr.optJSONObject(j) ?: continue
                posts.add(
                    PostEntry(
                        channelUsername = p.optString("channelUsername", ""),
                        success = p.optBoolean("success", false),
                        snippet = p.optString("snippet", ""),
                        message = p.optString("message", "")
                    )
                )
            }
            result.add(
                ScanEntry(
                    timestampMs = obj.optLong("timestampMs", 0L),
                    channelsChecked = obj.optInt("channelsChecked", 0),
                    posts = posts
                )
            )
        }
        return result.reversed()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SCANS).apply()
    }
}
