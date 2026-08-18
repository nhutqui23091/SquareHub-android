package com.squarehub.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Lưu danh sách kênh Telegram người dùng muốn tự động lấy bài đăng lên
// Square: bật/tắt tổng (master), bật/tắt riêng từng kênh, và ID bài viết cuối
// cùng đã thấy của mỗi kênh (để lần kiểm tra sau chỉ lấy bài MỚI, không quét
// lại từ đầu kênh mỗi lần).
object TelegramChannels {
    private const val PREFS_NAME = "square_hub_telegram"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_CHANNELS = "channels"

    data class Channel(
        val username: String,
        val enabled: Boolean,
        val lastPostId: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER_ENABLED, false)

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun getChannels(context: Context): List<Channel> {
        val raw = prefs(context).getString(KEY_CHANNELS, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
        val result = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val username = obj.optString("username", "")
            if (username.isBlank()) continue
            result.add(
                Channel(
                    username = username,
                    enabled = obj.optBoolean("enabled", true),
                    lastPostId = obj.optLong("lastPostId", 0L)
                )
            )
        }
        return result
    }

    private fun saveChannels(context: Context, channels: List<Channel>) {
        val arr = JSONArray()
        for (c in channels) {
            arr.put(
                JSONObject().apply {
                    put("username", c.username)
                    put("enabled", c.enabled)
                    put("lastPostId", c.lastPostId)
                }
            )
        }
        prefs(context).edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    // Chuẩn hoá tên kênh người dùng nhập vào: chấp nhận cả @tenkenh, link
    // t.me/tenkenh, hay chỉ mỗi "tenkenh" - đều quy về cùng 1 dạng để so
    // sánh/tránh trùng lặp.
    fun normalizeUsername(input: String): String {
        var value = input.trim()
        value = value
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("t.me/")
            .removePrefix("@")
        value = value.trim('/')
        value = value.substringBefore("/")
        value = value.substringBefore("?")
        return value.lowercase()
    }

    // Trả về false nếu tên kênh rỗng/không hợp lệ hoặc đã có trong danh sách.
    fun addChannel(context: Context, username: String): Boolean {
        val normalized = normalizeUsername(username)
        if (normalized.isBlank()) return false

        val channels = getChannels(context).toMutableList()
        if (channels.any { it.username == normalized }) return false

        channels.add(Channel(username = normalized, enabled = true, lastPostId = 0L))
        saveChannels(context, channels)
        return true
    }

    fun removeChannel(context: Context, username: String) {
        val channels = getChannels(context).filter { it.username != username }
        saveChannels(context, channels)
    }

    fun setChannelEnabled(context: Context, username: String, enabled: Boolean) {
        val channels = getChannels(context).map {
            if (it.username == username) it.copy(enabled = enabled) else it
        }
        saveChannels(context, channels)
    }

    // Chỉ tăng, không bao giờ giảm - phòng trường hợp 2 lần kiểm tra chạy gần
    // nhau đọc/ghi chồng lên nhau.
    fun updateLastPostId(context: Context, username: String, lastPostId: Long) {
        val channels = getChannels(context).map {
            if (it.username == username && lastPostId > it.lastPostId) {
                it.copy(lastPostId = lastPostId)
            } else {
                it
            }
        }
        saveChannels(context, channels)
    }
}
