package com.squarehub.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Lưu lịch sử đăng bài, dùng cho 2 việc:
// 1) Cảnh báo nếu lỡ đăng lại đúng bài X đã đăng lên Square rồi (tránh trùng).
// 2) Hiện tổng số bài đã đăng + số bài đã đăng trong hôm nay ở màn hình chính.
// Dữ liệu lưu trong SharedPreferences trên máy, không cần server riêng.
object PostStats {

    private const val PREFS_NAME = "square_hub_stats"
    private const val KEY_TOTAL = "total_post_count"
    private const val KEY_LOG = "post_log"
    private const val KEY_POSTED_TWEETS = "posted_tweets"

    // Giới hạn để tránh SharedPreferences phình to vô hạn theo thời gian.
    private const val MAX_LOG_ENTRIES = 3000
    private const val MAX_TWEET_ENTRIES = 2000

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    // Bài X (theo tweetId) này đã được đăng lên Square từ app này trước đó chưa.
    fun isAlreadyPosted(context: Context, tweetId: String?): Boolean {
        if (tweetId.isNullOrBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_POSTED_TWEETS, null) ?: return false
        return try {
            JSONObject(raw).has(tweetId)
        } catch (e: Exception) {
            false
        }
    }

    // Ghi nhận 1 lần đăng bài thành công: tăng tổng số bài, ghi ngày hôm nay
    // vào log (để tính số bài "đăng hôm nay"), và nếu có tweetId thì lưu lại
    // để chống đăng trùng về sau.
    fun recordSuccess(context: Context, tweetId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)

        val log = try {
            JSONArray(prefs.getString(KEY_LOG, null) ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        log.put(todayString())

        val trimmedLog = if (log.length() > MAX_LOG_ENTRIES) {
            val start = log.length() - MAX_LOG_ENTRIES
            JSONArray().apply {
                for (i in start until log.length()) put(log.optString(i))
            }
        } else {
            log
        }
        editor.putString(KEY_LOG, trimmedLog.toString())

        if (!tweetId.isNullOrBlank()) {
            val tweets = try {
                JSONObject(prefs.getString(KEY_POSTED_TWEETS, null) ?: "{}")
            } catch (e: Exception) {
                JSONObject()
            }
            tweets.put(tweetId, nowIso())

            // Xoá bớt mục cũ nhất nếu vượt ngưỡng lưu trữ.
            if (tweets.length() > MAX_TWEET_ENTRIES) {
                val keys = tweets.keys().asSequence().toMutableList()
                val toRemove = tweets.length() - MAX_TWEET_ENTRIES
                keys.take(toRemove).forEach { tweets.remove(it) }
            }

            editor.putString(KEY_POSTED_TWEETS, tweets.toString())
        }

        editor.apply()
    }

    data class Stats(val total: Int, val today: Int)

    fun getStats(context: Context): Stats {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val total = prefs.getInt(KEY_TOTAL, 0)

        val log = try {
            JSONArray(prefs.getString(KEY_LOG, null) ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val today = todayString()
        var todayCount = 0
        for (i in 0 until log.length()) {
            if (log.optString(i) == today) todayCount++
        }

        return Stats(total, todayCount)
    }
}
