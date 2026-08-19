package com.squarehub.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// Bật/tắt và lên lịch việc kiểm tra kênh Telegram định kỳ (mỗi 15 phút, tối
// thiểu WorkManager cho phép), sống sót qua khởi động lại máy nhờ
// androidx.work tự đăng ký nhận sự kiện BOOT_COMPLETED (đã khai báo quyền
// RECEIVE_BOOT_COMPLETED trong AndroidManifest).
object TelegramScheduler {
    // Đổi tên công việc khi thay đổi ràng buộc/lịch: enqueueUniquePeriodicWork
    // với KEEP sẽ GIỮ NGUYÊN lịch cũ đã đăng ký từ bản trước (không có ràng
    // buộc mạng), nên nếu giữ tên cũ thì máy vẫn chạy theo lịch cũ và bản vá
    // này vô tác dụng. Tên mới = lịch mới, đồng thời huỷ lịch cũ bên dưới.
    private const val LEGACY_WORK_NAME = "telegram_auto_post_worker"
    private const val WORK_NAME_V2 = "telegram_auto_post_worker_v2"

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)

        // Dọn lịch cũ (bản trước v10) để không chạy song song 2 lịch.
        manager.cancelUniqueWork(LEGACY_WORK_NAME)

        // Chỉ chạy khi máy THẬT SỰ có mạng. Trước đây thiếu ràng buộc này nên
        // ban đêm máy ngủ sâu/ngắt mạng, tác vụ vẫn chạy rồi báo lỗi
        // "Unable to resolve host t.me" hàng loạt. Có ràng buộc thì hệ thống
        // sẽ tự hoãn lại tới khi có mạng mới chạy.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<TelegramAutoPostWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        manager.enqueueUniquePeriodicWork(WORK_NAME_V2, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(WORK_NAME_V2)
        manager.cancelUniqueWork(LEGACY_WORK_NAME)
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

        // Cờ input để chạy quét thủ công (nút "Quét ngay" ở tab Telegram)
        // - bỏ qua điều kiện "đã bật công tắc tổng", vì mục đích lúc đó là test
        // xem đọc kênh có ra bài không, không phải chạy như lịch nền thật.
        const val INPUT_FORCE = "force"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(INPUT_FORCE, false)
        try {
            if (runCheck(force)) Result.retry() else Result.success()
        } catch (e: Exception) {
            // Không để 1 lỗi bất ngờ (kênh bị khoá, dữ liệu lạ...) làm hỏng cả
            // lịch chạy định kỳ - lần kiểm tra kế tiếp (15') sẽ tự chạy lại.
            Result.success()
        }
    }

    // Trả về true nếu lần quét này thất bại vì mất mạng và nên thử lại sớm
    // (Result.retry) thay vì đợi hết 15 phút của chu kỳ kế tiếp.
    private fun runCheck(force: Boolean): Boolean {
        val appContext = applicationContext

        if (!force && !TelegramChannels.isMasterEnabled(appContext)) return false

        val prefs = appContext.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(Config.API_KEY_PREF, "") ?: ""
        if (apiKey.isBlank()) return false

        val channels = TelegramChannels.getChannels(appContext).filter { it.enabled }
        if (channels.isEmpty()) return false

        // Máy đang không có mạng: không quét, không ghi 1 đống dòng đỏ giống
        // hệt nhau cho từng kênh. Chỉ ghi đúng 1 dòng cho cả lần quét rồi
        // hẹn thử lại - hệ thống sẽ tự chạy lại khi có mạng.
        if (!hasNetwork(appContext)) {
            TelegramScanLog.recordScan(
                appContext,
                channels.size,
                listOf(
                    TelegramScanLog.PostEntry(
                        channelUsername = "",
                        success = false,
                        snippet = "",
                        message = "Máy đang không có mạng, bỏ qua lần quét này (sẽ tự thử lại khi có mạng)",
                        isPostAttempt = false
                    )
                )
            )
            return true
        }

        val allEntries = mutableListOf<TelegramScanLog.PostEntry>()
        var networkFailures = 0

        for (channel in channels) {
            try {
                allEntries.addAll(checkChannel(appContext, channel, apiKey))
            } catch (e: Exception) {
                val networkProblem = isNetworkError(e)
                if (networkProblem) networkFailures++
                allEntries.add(
                    TelegramScanLog.PostEntry(
                        channelUsername = channel.username,
                        success = false,
                        snippet = "",
                        message = if (networkProblem) {
                            "Không kết nối được tới Telegram (mạng chập chờn), sẽ thử lại lần sau"
                        } else {
                            "Lỗi kiểm tra kênh: ${e.message ?: e.javaClass.simpleName}"
                        },
                        isPostAttempt = false
                    )
                )
            }
        }

        // Ghi lại lịch sử quét lần này (kể cả khi không có bài mới nào), để
        // người dùng tự xem lại app có thật sự đang chạy nền hay không.
        TelegramScanLog.recordScan(appContext, channels.size, allEntries)

        val postedCount = allEntries.count { it.success }
        // Chỉ báo thông báo khi có bài đăng được, hoặc khi bài đăng thất bại
        // thật sự - lỗi mạng tạm thời thì im lặng, tránh làm phiền.
        val postFailures = allEntries.count { !it.success && it.isPostAttempt }
        if (postedCount > 0 || postFailures > 0) {
            notifyResult(appContext, postedCount, postFailures)
        }

        // Tất cả các kênh đều hỏng vì mạng -> coi như lần quét này chưa chạy
        // được, hẹn thử lại sớm thay vì đợi hết chu kỳ.
        return networkFailures > 0 && networkFailures == channels.size
    }

    // Máy có đường mạng dùng được hay không (WiFi/di động đang kết nối).
    private fun hasNetwork(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = manager.activeNetwork ?: return false
                val caps = manager.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                manager.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            // Không kiểm tra được thì cứ cho chạy, để lỗi thật (nếu có) tự lộ ra.
            true
        }
    }

    // Lỗi thuộc nhóm "mạng" (mất mạng, DNS không phân giải được, timeout)
    // thay vì lỗi do kênh/nội dung.
    private fun isNetworkError(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 5) {
            if (cause is UnknownHostException || cause is SocketTimeoutException) return true
            if (cause is IOException) {
                val message = cause.message?.lowercase() ?: ""
                if (message.contains("unable to resolve host") ||
                    message.contains("failed to connect") ||
                    message.contains("network is unreachable") ||
                    message.contains("timeout") ||
                    message.contains("connection reset")
                ) return true
            }
            cause = cause.cause
            depth++
        }
        return false
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

        // Đếm tổng số khối tin nhắn đọc được trên trang (kể cả thông báo hệ
        // thống), để phân biệt "kênh không đọc được gì" với "kênh chỉ toàn
        // thông báo hệ thống, chưa có bài đăng thật".
        val rawBlockCount = doc.select("div.tgme_widget_message[data-post]").size

        val messages = mutableListOf<Msg>()
        // Bỏ qua thông báo hệ thống của Telegram (class service_message):
        // "Channel created", "Messages in this channel will be automatically
        // deleted after 1 month"... - đây không phải bài đăng của chủ kênh.
        for (div in doc.select("div.tgme_widget_message[data-post]:not(.service_message)")) {
            val dataPost = div.attr("data-post")
            val postId = dataPost.substringAfterLast("/").toLongOrNull() ?: continue

            val rawHtml = div.selectFirst("div.tgme_widget_message_text")?.html() ?: ""
            val text = TextCleaner.htmlFragmentToPlainText(rawHtml)

            // Chốt chặn thứ 2 theo nội dung, phòng khi Telegram đổi tên class
            // hoặc thông báo hệ thống không mang class service_message.
            if (TextCleaner.isTelegramServiceMessage(text)) continue

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

        if (messages.isEmpty()) {
            // Đọc được khối tin nhắn nhưng toàn là thông báo hệ thống -> kênh
            // đọc bình thường, chỉ là chưa có bài đăng thật nào. Không phải lỗi.
            if (rawBlockCount > 0) return emptyList()

            // Không đọc được khối tin nhắn NÀO trên trang: nhiều khả năng tên
            // kênh sai, hoặc là nhóm/kênh riêng tư (Telegram không cho xem
            // trước công khai) - báo rõ để dò lỗi, thay vì im lặng trông
            // giống hệt "không có bài mới".
            return listOf(
                TelegramScanLog.PostEntry(
                    channelUsername = channel.username,
                    success = false,
                    snippet = "",
                    message = "Không đọc được bài nào từ t.me/s/${channel.username} " +
                        "(kênh phải là Channel CÔNG KHAI - nhóm/kênh riêng tư sẽ không đọc được; " +
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
