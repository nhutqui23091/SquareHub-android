package com.squarehub.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

// Bật/tắt và lên lịch việc kiểm tra kênh Telegram định kỳ (mỗi 15 phút, tối
// thiểu WorkManager cho phép), sống sót qua khởi động lại máy nhờ
// androidx.work tự đăng ký nhận sự kiện BOOT_COMPLETED (đã khai báo quyền
// RECEIVE_BOOT_COMPLETED trong AndroidManifest).
object TelegramScheduler {
    private const val WORK_NAME = "telegram_auto_post_worker"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TelegramAutoPostWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // Nếu đã có lịch chạy rồi thì giữ nguyên, không tạo chồng lịch mới
            // mỗi lần mở app lên.
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

// Chạy định kỳ trong nền (do TelegramScheduler lên lịch) để tự động kiểm tra
// các kênh Telegram người dùng đã thêm, lấy bài mới rồi tự đăng lên Binance
// Square. Không cần đăng nhập tài khoản Telegram nào - chỉ đọc trang xem
// trước công khai t.me/s/<kenh> (ai cũng xem được qua trình duyệt, kể cả khi
// không có tài khoản Telegram).
class TelegramAutoPostWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private data class Msg(
        val postId: Long,
        val text: String,
        val photoUrls: List<String>,
        val videoUrl: String?
    )

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val NOTIFICATION_CHANNEL_ID = "telegram_auto_post"
        private const val NOTIFICATION_ID = 9001
        private const val SNIPPET_LENGTH = 60

        // Cờ input để chạy quét thủ công (nút "Quét ngay" trong TelegramActivity)
        // - bỏ qua điều kiện "đã bật công tắc tổng", vì mục đích lúc đó là test
        // xem đọc kênh có ra bài không, không phải chạy như lịch nền thật.
        const val INPUT_FORCE = "force"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(INPUT_FORCE, false)
        try {
            runCheck(force)
        } catch (e: Exception) {
            // Không để 1 lỗi bất ngờ (mất mạng, kênh bị khoá...) làm hỏng cả
            // lịch chạy định kỳ - lần kiểm tra kế tiếp (15') sẽ tự chạy lại.
        }
        Result.success()
    }

    private fun runCheck(force: Boolean) {
        val appContext = applicationContext

        if (!force && !TelegramChannels.isMasterEnabled(appContext)) return

        val prefs = appContext.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(Config.API_KEY_PREF, "") ?: ""
        if (apiKey.isBlank()) return

        val channels = TelegramChannels.getChannels(appContext).filter { it.enabled }
        if (channels.isEmpty()) return

        val allEntries = mutableListOf<TelegramScanLog.PostEntry>()

        for (channel in channels) {
            try {
                allEntries.addAll(checkChannel(appContext, channel, apiKey))
            } catch (e: Exception) {
                allEntries.add(
                    TelegramScanLog.PostEntry(
                        channelUsername = channel.username,
                        success = false,
                        snippet = "",
                        message = "Lỗi kiểm tra kênh: ${e.message ?: e.javaClass.simpleName}"
                    )
                )
            }
        }

        // Ghi lại lịch sử quét lần này (kể cả khi không có bài mới nào), để
        // người dùng tự xem lại app có thật sự đang chạy nền hay không.
        TelegramScanLog.recordScan(appContext, channels.size, allEntries)

        val postedCount = allEntries.count { it.success }
        val errorCount = allEntries.count { !it.success }
        if (postedCount > 0 || errorCount > 0) {
            notifyResult(appContext, postedCount, errorCount)
        }
    }

    // Trả về danh sách kết quả đăng bài (nếu có) cho riêng kênh này, để ghi
    // vào lịch sử quét.
    private fun checkChannel(
        context: Context,
        channel: TelegramChannels.Channel,
        apiKey: String
    ): List<TelegramScanLog.PostEntry> {
        val doc = Jsoup.connect("https://t.me/s/${channel.username}")
            .userAgent(DESKTOP_USER_AGENT)
            .timeout(20000)
            .get()

        val messages = mutableListOf<Msg>()
        for (div in doc.select("div.tgme_widget_message[data-post]")) {
            val dataPost = div.attr("data-post")
            val postId = dataPost.substringAfterLast("/").toLongOrNull() ?: continue

            val rawHtml = div.selectFirst("div.tgme_widget_message_text")?.html() ?: ""
            val text = TextCleaner.htmlFragmentToPlainText(rawHtml)

            val photoUrls = mutableListOf<String>()
            for (photoEl in div.select("a.tgme_widget_message_photo_wrap")) {
                val style = photoEl.attr("style")
                Regex("url\\('(.+?)'\\)").find(style)?.groupValues?.get(1)?.let { photoUrls.add(it) }
            }

            val videoUrl = div.selectFirst("video.tgme_widget_message_video")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }

            messages.add(Msg(postId, text, photoUrls, videoUrl))
        }

        // Nếu không đọc được bài nào cả (khác 0 bài mới - đây là KHÔNG đọc
        // được bài NÀO trên trang), rất có thể trang t.me/s/ đã đổi cấu trúc
        // hoặc tên kênh sai/kênh riêng tư - báo rõ để dò lỗi, thay vì im lặng
        // trông giống hệt "không có bài mới".
        if (messages.isEmpty()) {
            return listOf(
                TelegramScanLog.PostEntry(
                    channelUsername = channel.username,
                    success = false,
                    snippet = "",
                    message = "Không đọc được bài nào từ t.me/s/${channel.username} " +
                        "(kênh phải là kênh CÔNG KHAI - private/nhóm riêng tư sẽ không đọc được; " +
                        "hoặc kiểm tra lại tên kênh có đúng không)"
                )
            )
        }

        val maxSeenId = messages.maxOf { it.postId }

        // Lần đầu tiên theo dõi kênh này (chưa từng chạy): đăng luôn bài mới
        // nhất hiện có (không đăng lại toàn bộ lịch sử, tránh spam), rồi lấy
        // mốc từ bài đó trở đi cho các lần quét sau.
        if (channel.lastPostId == 0L) {
            TelegramChannels.updateLastPostId(context, channel.username, maxSeenId)
            val latest = messages.maxByOrNull { it.postId } ?: return emptyList()
            return processMessage(context, channel, latest, apiKey)?.let { listOf(it) } ?: emptyList()
        }

        val newMessages = messages
            .filter { it.postId > channel.lastPostId }
            .sortedBy { it.postId }

        if (newMessages.isEmpty()) return emptyList()

        val entries = mutableListOf<TelegramScanLog.PostEntry>()
        for (msg in newMessages) {
            // Luôn cập nhật mốc lastPostId dù bỏ qua/thành công/thất bại, để
            // tránh kẹt lại mãi ở 1 bài và không bao giờ qua được các bài sau.
            TelegramChannels.updateLastPostId(context, channel.username, msg.postId)
            processMessage(context, channel, msg, apiKey)?.let { entries.add(it) }
        }
        return entries
    }

    // Trả về null nếu bỏ qua (đã đăng trùng trước đó / không có nội dung gì
    // để đăng), hoặc 1 PostEntry ghi lại kết quả đăng để hiện trong lịch sử
    // quét.
    private fun processMessage(
        context: Context,
        channel: TelegramChannels.Channel,
        msg: Msg,
        apiKey: String
    ): TelegramScanLog.PostEntry? {
        val postId = TextCleaner.telegramPostId(channel.username, msg.postId)
        if (PostStats.isAlreadyPosted(context, postId)) return null

        val cleanedText = TextCleaner.cleanText(msg.text)
        val hasContent = cleanedText.isNotBlank() || msg.photoUrls.isNotEmpty() || msg.videoUrl != null
        if (!hasContent) return null

        val result = SquareApi.postRemoteContent(
            context,
            cleanedText,
            msg.photoUrls.take(4),
            msg.videoUrl,
            apiKey
        )

        if (result.success) {
            PostStats.recordSuccess(context, postId)
        }

        val snippet = when {
            cleanedText.isNotBlank() -> {
                if (cleanedText.length > SNIPPET_LENGTH) cleanedText.take(SNIPPET_LENGTH) + "…" else cleanedText
            }
            msg.videoUrl != null -> "(video, không có chữ)"
            else -> "(ảnh, không có chữ)"
        }

        return TelegramScanLog.PostEntry(
            channelUsername = channel.username,
            success = result.success,
            snippet = snippet,
            message = result.message
        )
    }

    private fun notifyResult(context: Context, posted: Int, errors: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tự động đăng Telegram",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val text = buildString {
            if (posted > 0) append("Đã tự động đăng $posted bài mới lên Square")
            if (errors > 0) {
                if (isNotEmpty()) append(", ")
                append("$errors lỗi")
            }
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SquareHub")
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        manager.notify(NOTIFICATION_ID, notification)
    }
}
