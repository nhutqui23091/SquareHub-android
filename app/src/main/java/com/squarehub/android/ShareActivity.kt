package com.squarehub.android

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern

// Không hiện khung xem trước nữa - bấm chia sẻ là tự động đăng luôn,
// chỉ báo kết quả bằng 1 dòng thông báo nhỏ (Toast) rồi tự đóng lại.
class ShareActivity : AppCompatActivity() {

    private var sharedText: String = ""
    private var sharedUrl: String? = null

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

        if (streamUri != null && type.startsWith("image/")) {
            localImageUris = mutableListOf(streamUri)
            sharedText = text
            startPosting()
            return
        }

        if (streamUri != null && type.startsWith("video/")) {
            localVideoUri = streamUri
            sharedText = text
            startPosting()
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
            fetchTweetThenPost(sharedUrl!!)
        } else {
            startPosting()
        }
    }

    private fun handleMultipleSend() {
        val uris = getStreamUriList(intent) ?: arrayListOf()
        localImageUris = uris.take(4).toMutableList()
        sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
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
                    sharedText = tweetText ?: ""
                } else {
                    sharedText = content.text

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

            toast(if (result.success) "✅ ${result.message}" else "❌ ${result.message}")
            finish()
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
            val dl = SquareApi.downloadBytesDebug(
                remoteVideoUrl!!,
                accept = "video/mp4,video/*;q=0.9,*/*;q=0.1"
            )
            val bytes = dl.bytes
                ?: return SquarePostResult(false, null, "Không tải được video từ bài viết (${dl.info})")
            // Dùng đúng Content-Type thật (mp4/webm) lấy từ response, tránh
            // lệch định dạng khi upload lên Binance.
            val videoContentType = SquareApi.normalizeVideoContentType(dl.contentType)
            val videoExt = SquareApi.extensionForVideoContentType(videoContentType)
            return postVideoBytes(bytes, "tweet_video.$videoExt", apiKey, videoContentType)
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

        if (remotePhotoUrls.isNotEmpty()) {
            val urls = mutableListOf<String>()
            var lastError = ""
            for ((index, photoUrl) in remotePhotoUrls.withIndex()) {
                val dl = SquareApi.downloadBytesDebug(photoUrl, accept = "image/*")
                if (dl.bytes == null) {
                    lastError = dl.info
                    continue
                }
                // Dùng đúng Content-Type thật (jpeg/png/webp/gif) lấy từ
                // response, tránh lệch định dạng khiến upload lên Binance
                // (presigned S3 URL) bị từ chối.
                val imageContentType = SquareApi.normalizeImageContentType(dl.contentType, photoUrl)
                val imageExt = SquareApi.extensionForImageContentType(imageContentType)
                val uploaded = SquareApi.uploadImageBytes(
                    dl.bytes,
                    "tweet_image_$index.$imageExt",
                    apiKey,
                    imageContentType
                )
                if (uploaded.value != null) urls.add(uploaded.value) else lastError = uploaded.error ?: "upload lên Binance thất bại"
            }
            if (urls.isEmpty()) return SquarePostResult(false, null, "Không tải được ảnh từ bài viết ($lastError)")
            return SquareApi.postImages(sharedText, urls, apiKey)
        }

        if (sharedText.isBlank()) {
            return SquarePostResult(false, null, "Không có nội dung để đăng")
        }

        return SquareApi.postText(sharedText, apiKey)
    }

    private fun postVideoBytes(
        bytes: ByteArray,
        fileName: String,
        apiKey: String,
        contentType: String? = null
    ): SquarePostResult {
        val videoUpload = SquareApi.uploadVideoBytes(bytes, fileName, apiKey, contentType)
        val fileTicket = videoUpload.value
            ?: return SquarePostResult(false, null, "Không tải video lên được (${videoUpload.error})")

        val info = extractVideoInfo(bytes)
        val bitmap = info.coverBitmap
            ?: return SquarePostResult(false, null, "Không tạo được ảnh bìa cho video")

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)

        val coverUpload = SquareApi.uploadImageBytes(output.toByteArray(), "video_cover.jpg", apiKey)
        val coverUrl = coverUpload.value
            ?: return SquarePostResult(false, null, "Không tải được ảnh bìa video (${coverUpload.error})")

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
