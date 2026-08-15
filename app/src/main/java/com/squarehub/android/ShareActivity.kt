package com.squarehub.android

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern

class ShareActivity : AppCompatActivity() {

    private var sharedText: String = ""
    private var sharedUrl: String? = null

    // Ảnh/video lấy trực tiếp từ intent chia sẻ (nếu app nguồn đính kèm sẵn)
    private var localImageUris: MutableList<Uri> = mutableListOf()
    private var localVideoUri: Uri? = null

    // Ảnh/video lấy được bằng cách tự tải nội dung bài viết từ link (khi
    // app nguồn chỉ đưa link, không đính kèm file - trường hợp phổ biến của X)
    private var remotePhotoUrls: List<String> = emptyList()
    private var remoteVideoUrl: String? = null

    private lateinit var previewText: TextView
    private lateinit var postButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        previewText = findViewById(R.id.previewText)
        postButton = findViewById(R.id.postButton)
        cancelButton = findViewById(R.id.cancelButton)
        progressBar = findViewById(R.id.progressBar)

        cancelButton.setOnClickListener { finish() }
        postButton.setOnClickListener { post() }

        handleIncomingIntent()
    }

    // ---------- Nhận nội dung được chia sẻ ----------

    private fun handleIncomingIntent() {
        when (intent.action) {
            Intent.ACTION_SEND -> handleSingleSend()
            Intent.ACTION_SEND_MULTIPLE -> handleMultipleSend()
            else -> previewText.text = "Không có nội dung để đăng."
        }
    }

    private fun handleSingleSend() {
        val type = intent.type ?: ""
        val streamUri = getStreamUri(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""

        if (streamUri != null && type.startsWith("image/")) {
            localImageUris = mutableListOf(streamUri)
            sharedText = text
            refreshLocalMediaPreview()
            return
        }

        if (streamUri != null && type.startsWith("video/")) {
            localVideoUri = streamUri
            sharedText = text
            refreshLocalMediaPreview()
            return
        }

        // Trường hợp text/plain - thường gặp khi share 1 bài từ X, lúc này
        // thường chỉ có link, chưa có nội dung/ảnh/video thật.
        sharedText = text

        val matcher = Pattern.compile("https?://\\S+").matcher(text)
        if (matcher.find()) {
            sharedUrl = matcher.group()
        }

        val isOnlyUrl = sharedUrl != null && sharedText.trim() == sharedUrl
        val isXLink = sharedUrl?.let {
            it.contains("twitter.com") || it.contains("x.com")
        } == true

        if (isOnlyUrl && isXLink) {
            fetchAndShowTweetContent(sharedUrl!!)
        } else {
            previewText.text = sharedText.ifBlank { "Không có nội dung để đăng." }
        }
    }

    private fun handleMultipleSend() {
        val uris = getStreamUriList(intent) ?: arrayListOf()
        localImageUris = uris.take(4).toMutableList()
        sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        refreshLocalMediaPreview()
    }

    private fun refreshLocalMediaPreview() {
        val parts = mutableListOf<String>()

        if (sharedText.isNotBlank()) parts.add(sharedText)
        if (localImageUris.isNotEmpty()) parts.add("🖼️ ${localImageUris.size} ảnh đính kèm")
        if (localVideoUri != null) parts.add("🎥 Video đính kèm")

        previewText.text = if (parts.isEmpty()) "Không có nội dung để đăng." else parts.joinToString("\n")
    }

    private fun fetchAndShowTweetContent(url: String) {
        previewText.text = "Đang lấy nội dung bài viết..."

        CoroutineScope(Dispatchers.Main).launch {
            val content = withContext(Dispatchers.IO) { SquareApi.fetchTweetContent(url) }

            if (content == null) {
                val tweetText = withContext(Dispatchers.IO) { SquareApi.fetchTweetText(url) }
                sharedText = if (!tweetText.isNullOrBlank()) "$tweetText\n\n$url" else url
                previewText.text = sharedText
                return@launch
            }

            sharedText = if (content.text.isNotBlank()) "${content.text}\n\n$url" else url

            when {
                content.videoUrl != null -> {
                    remoteVideoUrl = content.videoUrl
                    previewText.text = "$sharedText\n\n🎥 Video đính kèm"
                }
                content.photoUrls.isNotEmpty() -> {
                    remotePhotoUrls = content.photoUrls.take(4)
                    previewText.text = "$sharedText\n\n🖼️ ${remotePhotoUrls.size} ảnh đính kèm"
                }
                else -> previewText.text = sharedText
            }
        }
    }

    // ---------- Đăng bài ----------

    private fun post() {
        val prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(Config.API_KEY_PREF, "") ?: ""

        if (apiKey.isBlank()) {
            previewText.text = "⚠️ Chưa có API key. Mở app SquareHub để lưu key."
            return
        }

        postButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { doPost(apiKey) }

            progressBar.visibility = View.GONE
            postButton.isEnabled = true

            previewText.text = if (result.success) {
                "✅ ${result.message}\nID: ${result.postId ?: "unavailable"}"
            } else {
                "❌ ${result.message}"
            }

            if (result.success) {
                previewText.postDelayed({ finish() }, 1200)
            }
        }
    }

    // Ưu tiên: video > ảnh > chỉ text. Binance Square không cho đăng
    // ảnh và video cùng lúc trong 1 bài.
    private fun doPost(apiKey: String): SquarePostResult {
        if (localVideoUri != null) {
            val bytes = readUriBytes(localVideoUri!!)
                ?: return SquarePostResult(false, null, "Không đọc được file video")
            return postVideoBytes(bytes, "share_video.mp4", apiKey)
        }

        if (remoteVideoUrl != null) {
            val bytes = SquareApi.downloadBytes(remoteVideoUrl!!)
                ?: return SquarePostResult(false, null, "Không tải được video từ bài viết")
            return postVideoBytes(bytes, "tweet_video.mp4", apiKey)
        }

        if (localImageUris.isNotEmpty()) {
            val urls = mutableListOf<String>()
            for ((index, uri) in localImageUris.withIndex()) {
                val bytes = readUriBytes(uri) ?: continue
                SquareApi.uploadImageBytes(bytes, "share_image_$index.jpg", apiKey)?.let { urls.add(it) }
            }
            if (urls.isEmpty()) return SquarePostResult(false, null, "Không tải ảnh lên được")
            return SquareApi.postImages(sharedText, urls, apiKey)
        }

        if (remotePhotoUrls.isNotEmpty()) {
            val urls = mutableListOf<String>()
            for ((index, photoUrl) in remotePhotoUrls.withIndex()) {
                val bytes = SquareApi.downloadBytes(photoUrl) ?: continue
                SquareApi.uploadImageBytes(bytes, "tweet_image_$index.jpg", apiKey)?.let { urls.add(it) }
            }
            if (urls.isEmpty()) return SquarePostResult(false, null, "Không tải được ảnh từ bài viết")
            return SquareApi.postImages(sharedText, urls, apiKey)
        }

        if (sharedText.isBlank()) {
            return SquarePostResult(false, null, "Không có nội dung để đăng")
        }

        return SquareApi.postText(sharedText, apiKey)
    }

    private fun postVideoBytes(bytes: ByteArray, fileName: String, apiKey: String): SquarePostResult {
        val fileTicket = SquareApi.uploadVideoBytes(bytes, fileName, apiKey)
            ?: return SquarePostResult(false, null, "Không tải video lên được")

        val info = extractVideoInfo(bytes)
        val bitmap = info.coverBitmap
            ?: return SquarePostResult(false, null, "Không tạo được ảnh bìa cho video")

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)

        val coverUrl = SquareApi.uploadImageBytes(output.toByteArray(), "video_cover.jpg", apiKey)
            ?: return SquarePostResult(false, null, "Không tải được ảnh bìa video")

        return SquareApi.postVideo(sharedText, fileTicket, coverUrl, info.durationSeconds, apiKey)
    }

    private data class VideoInfo(val coverBitmap: Bitmap?, val durationSeconds: Double)

    private fun extractVideoInfo(videoBytes: ByteArray): VideoInfo {
        var tempFile: File? = null

        return try {
            tempFile = File.createTempFile("square_video", ".mp4", cacheDir)
            tempFile.writeBytes(videoBytes)

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)

            val frame = retriever.getFrameAtTime(0)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            retriever.release()

            VideoInfo(frame, durationMs / 1000.0)
        } catch (e: Exception) {
            VideoInfo(null, 0.0)
        } finally {
            tempFile?.delete()
        }
    }

    private fun readUriBytes(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
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
