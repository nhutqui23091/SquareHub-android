package com.squarehub.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

// Không hiện khung xem trước nữa - bấm chia sẻ là tự động đăng luôn,
// chỉ báo kết quả bằng 1 dòng thông báo nhỏ (Toast) rồi tự đóng lại.
class ShareActivity : AppCompatActivity() {

    private var sharedText: String = ""
    private var sharedUrl: String? = null

    // ID của bài X đang đăng (nếu có), dùng để cảnh báo chống đăng trùng và
    // để đếm thống kê.
    private var tweetId: String? = null

    private var localImageUris: MutableList<Uri> = mutableListOf()
    private var localVideoUri: Uri? = null

    private var remotePhotoUrls: List<String> = emptyList()
    private var remoteVideoUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            handleIncomingIntent()
        } catch (e: Exception) {
            Log.e("SquareHub", "onCreate failed", e)
            toast("Lỗi: ${e.message ?: e.javaClass.simpleName}")
            finish()
        }
    }

    // ---------- Nhận nội dung được chia sẻ ----------

    private fun handleIncomingIntent() {
        when (intent.action) {
            Intent.ACTION_SEND -> handleSingleSend()
            Intent.ACTION_SEND_MULTIPLE -> handleMultipleSend()
            else -> {
                toast("Không có nội dung để đăng.")
                finish()
            }
        }
    }

    private fun handleSingleSend() {
        val type = intent.type ?: ""
        val streamUri = getStreamUri(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""

        // X/Telegram thường kèm link bài viết trong phần text ngay cả khi
        // chia sẻ kèm ảnh/video, nên luôn thử lấy ID bài ở đây để chống
        // đăng trùng.
        TextCleaner.extractPostId(text)?.let { tweetId = it }

        if (streamUri != null && type.startsWith("image/")) {
            localImageUris = mutableListOf(streamUri)
            sharedText = TextCleaner.cleanText(text)
            startPosting()
            return
        }

        if (streamUri != null && type.startsWith("video/")) {
            localVideoUri = streamUri
            sharedText = TextCleaner.cleanText(text)
            startPosting()
            return
        }

        // Trường hợp text/plain - thường gặp khi share 1 bài từ X, lúc này
        // thường chỉ có link, chưa có nội dung/ảnh/video thật.
        sharedText = TextCleaner.cleanText(text)

        val matcher = Pattern.compile("https?://\\S+").matcher(text)
        if (matcher.find()) {
            sharedUrl = matcher.group()
        }

        val isOnlyUrl = sharedUrl != null && text.trim() == sharedUrl
        val isXLink = sharedUrl?.let {
            it.contains("twitter.com") || it.contains("x.com")
        } == true

        if (isOnlyUrl && isXLink) {
            fetchTweetThenPost(sharedUrl!!)
        } else {
            startPosting()
        }
    }

    private fun handleMultipleSend() {
        val uris = getStreamUriList(intent) ?: arrayListOf()
        localImageUris = uris.take(4).toMutableList()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        TextCleaner.extractPostId(text)?.let { tweetId = it }
        sharedText = TextCleaner.cleanText(text)
        startPosting()
    }

    // Lấy nội dung thật (text/ảnh/video) từ link bài viết X - không giữ
    // lại link gốc trong nội dung đăng, chỉ lấy đúng text/ảnh/video.
    private fun fetchTweetThenPost(url: String) {
        toast("Đang lấy nội dung bài viết...")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val content = withContext(Dispatchers.IO) { SquareApi.fetchTweetContent(url) }

                if (content == null) {
                    val tweetText = withContext(Dispatchers.IO) { SquareApi.fetchTweetText(url) }
                    sharedText = TextCleaner.cleanText(tweetText ?: "")
                } else {
                    sharedText = TextCleaner.cleanText(content.text)

                    if (content.videoUrl != null) {
                        remoteVideoUrl = content.videoUrl
                    } else if (content.photoUrls.isNotEmpty()) {
                        remotePhotoUrls = content.photoUrls.take(4)
                    }
                }
            } catch (e: Exception) {
                Log.e("SquareHub", "fetchTweetThenPost failed", e)
            }

            startPosting()
        }
    }

    // ---------- Đăng bài ----------

    private fun startPosting() {
        val prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(Config.API_KEY_PREF, "") ?: ""

        if (apiKey.isBlank()) {
            toast("⚠️ Chưa có API key. Mở app SquareHub để lưu key.")
            finish()
            return
        }

        // Cảnh báo nếu bài X này đã được đăng lên Square từ app này trước
        // đó rồi, tránh lỡ tay đăng trùng.
        if (PostStats.isAlreadyPosted(this, tweetId)) {
            toast("⚠️ Bài này đã được đăng lên Square trước đó rồi, không đăng lại.")
            finish()
            return
        }

        val hasAnyContent = sharedText.isNotBlank() ||
            localImageUris.isNotEmpty() ||
            localVideoUri != null ||
            remotePhotoUrls.isNotEmpty() ||
            remoteVideoUrl != null

        if (!hasAnyContent) {
            toast("Không có nội dung để đăng.")
            finish()
            return
        }

        toast("Đang đăng lên Square...")

        CoroutineScope(Dispatchers.Main).launch {
            val result = try {
                withContext(Dispatchers.IO) { doPost(apiKey) }
            } catch (e: Exception) {
                Log.e("SquareHub", "post failed", e)
                SquarePostResult(false, null, "Lỗi: ${e.message ?: e.javaClass.simpleName}")
            }

            if (result.success) {
                PostStats.recordSuccess(this@ShareActivity, tweetId)
            }

            toast(if (result.success) "✅ ${result.message}" else "❌ ${result.message}")
            finish()
        }
    }

    // Ưu tiên: video > ảnh > chỉ text. Binance Square không cho đăng
    // ảnh và video cùng lúc trong 1 bài. Nhánh ảnh/video từ xa (lấy được từ
    // link bài X) và text-only dùng chung logic với luồng tự động Telegram,
    // qua SquareApi.postRemoteContent.
    private fun doPost(apiKey: String): SquarePostResult {
        if (localVideoUri != null) {
            val bytes = readUriBytes(localVideoUri!!)
                ?: return SquarePostResult(false, null, "Không đọc được file video")
            return SquareApi.postVideoBytesFromContext(this, sharedText, bytes, "share_video.mp4", apiKey)
        }

        if (localImageUris.isNotEmpty()) {
            val urls = mutableListOf<String>()
            var lastError = ""
            for ((index, uri) in localImageUris.withIndex()) {
                val bytes = readUriBytes(uri)
                if (bytes == null) {
                    lastError = "không đọc được file"
                    continue
                }
                val uploaded = SquareApi.uploadImageBytes(bytes, "share_image_$index.jpg", apiKey)
                if (uploaded.value != null) urls.add(uploaded.value) else lastError = uploaded.error ?: "upload thất bại"
            }
            if (urls.isEmpty()) return SquarePostResult(false, null, "Không tải ảnh lên được ($lastError)")
            return SquareApi.postImages(sharedText, urls, apiKey)
        }

        return SquareApi.postRemoteContent(this, sharedText, remotePhotoUrls, remoteVideoUrl, apiKey)
    }

    private fun readUriBytes(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---------- Helpers tương thích API cũ/mới ----------

    private fun getStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun getStreamUriList(intent: Intent): ArrayList<Uri>? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
    }
}
