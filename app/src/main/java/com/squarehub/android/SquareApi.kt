package com.squarehub.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class SquarePostResult(
    val success: Boolean,
    val postId: String?,
    val message: String
)

data class TweetContent(
    val text: String,
    val photoUrls: List<String>,
    val videoUrl: String?,
    val videoPosterUrl: String?
)

// Endpoint/luồng gọi API lấy đúng theo source code chính thức của Binance
// tại github.com/binance/binance-skills-hub (skills/binance/square-post).
//
// Việc tải ảnh/video từ X (pbs.twimg.com, video.twimg.com) trong file này được
// viết lại để mô phỏng đúng cách Chrome extension "Square Hub" (đã xác nhận
// chạy tốt, không lỗi) đang làm: dùng User-Agent trình duyệt máy tính, KHÔNG
// gửi Referer giả (có thể bị chặn nếu không khớp phiên thật), Accept header
// đúng loại media, và thử nhiều kiểu token khi lấy dữ liệu bài viết.
object SquareApi {

    private const val ENDPOINT_V1 = "https://www.binance.com/bapi/composite/v1/public/pgc/openApi"
    private const val ENDPOINT_V2 = "https://www.binance.com/bapi/composite/v2/public/pgc/openApi"

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ---------- Đăng bài ----------

    fun postText(text: String, apiKey: String): SquarePostResult {
        val body = JSONObject().apply { put("bodyTextOnly", text) }
        return publishContent(body, apiKey)
    }

    fun postImages(text: String, imageUrls: List<String>, apiKey: String): SquarePostResult {
        val body = JSONObject().apply {
            put("contentType", 1)
            put("bodyTextOnly", text)
            put("imageList", JSONArray(imageUrls))
        }
        return publishContent(body, apiKey)
    }

    fun postVideo(
        text: String,
        fileTicket: String,
        cover: String,
        videoTimeSeconds: Double,
        apiKey: String
    ): SquarePostResult {
        val body = JSONObject().apply {
            put("contentType", 3)
            put("fileTicket", fileTicket)
            put("cover", cover)
            put("videoTimeSeconds", videoTimeSeconds)
            put("isPublish", true)
            put("bodyTextOnly", text)
        }
        return publishContent(body, apiKey)
    }

    private fun publishContent(body: JSONObject, apiKey: String): SquarePostResult {
        val resp = postJson("$ENDPOINT_V1/content/add", body, apiKey)
            ?: return SquarePostResult(false, null, "Không kết nối được tới Binance Square")

        return if (resp.optString("code") == "000000") {
            val id = resp.optJSONObject("data")?.opt("id")?.toString()
            SquarePostResult(true, id, "Đã đăng lên Binance Square")
        } else {
            SquarePostResult(false, null, resp.optString("message", "Binance API error"))
        }
    }

    // Kết quả upload có kèm lý do lỗi cụ thể (mã HTTP + nội dung lỗi trả về từ
    // Binance/S3), để hiện lên Toast cho người dùng thấy chính xác chỗ nào
    // đang bị chặn, thay vì chỉ biết chung chung là "thất bại".
    data class UploadResult(val value: String?, val error: String?)

    // ---------- Upload ảnh ----------

    fun uploadImageBytes(
        bytes: ByteArray,
        fileName: String,
        apiKey: String,
        contentType: String? = null
    ): UploadResult {
        return try {
            val presignBody = JSONObject().apply { put("imageName", fileName) }
            val presign = postJsonDebug("$ENDPOINT_V2/image/presignedUrl", presignBody, apiKey)
            val presignResp = presign.json
                ?: return UploadResult(null, "xin presigned URL thất bại (${presign.info})")

            apiErrorMessage(presignResp)?.let { return UploadResult(null, it) }

            // QUAN TRỌNG: Binance luôn bọc dữ liệu thật trong field "data"
            // (vd {code, message, data:{presignedUrl, fileTicket}}), không
            // nằm trực tiếp ở cấp ngoài cùng. Đây là nguyên nhân gốc khiến
            // upload ảnh luôn thất bại từ trước đến giờ dù tải ảnh về đã ổn.
            val presignData = presignResp.optJSONObject("data") ?: presignResp
            val presignedUrl = presignData.optString("presignedUrl", "")
            val fileTicket = presignData.optString("fileTicket", "")
            if (presignedUrl.isBlank() || fileTicket.isBlank()) {
                return UploadResult(null, "Binance không trả về presigned URL cho ảnh (phản hồi: ${presignResp.toString().take(200)})")
            }

            // Content-Type khi PUT lên phải khớp với đuôi file đã báo lúc xin
            // presigned URL, nếu không S3 sẽ từ chối upload.
            val effectiveContentType = contentType ?: contentTypeForFileName(fileName)
            val put = putBytesDebug(presignedUrl, bytes, effectiveContentType)
            if (!put.success) {
                return UploadResult(null, "upload ảnh lên S3 thất bại (${put.info})")
            }

            val statusResult = pollFileStatus(fileTicket, apiKey)
            val imageUrl = statusResult.value
                ?: return UploadResult(null, statusResult.error ?: "Binance xử lý ảnh thất bại")

            val url = imageUrl.optString("imageUrl", "")
            if (url.isBlank()) {
                return UploadResult(null, "Binance báo xử lý xong nhưng không có URL ảnh")
            }

            UploadResult(url, null)
        } catch (e: Exception) {
            UploadResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    // Chờ Binance xử lý xong file đã upload (status == 1) rồi mới coi là sẵn
    // sàng dùng. QUAN TRỌNG: trước đây app chỉ cần thấy field imageUrl không
    // rỗng là coi như xong, nhưng Binance có thể trả imageUrl sớm trước khi
    // xử lý xong hẳn, khiến bước đăng bài sau đó báo lỗi "Upload failed".
    // Phải đợi đúng status == 1 mới được dùng, giống hệt cách Chrome
    // extension đã chạy tốt đang làm.
    private data class StatusResult(val value: JSONObject?, val error: String?)

    private fun pollFileStatus(fileTicket: String, apiKey: String, maxAttempts: Int = 20): StatusResult {
        var lastInfo = ""
        for (attempt in 1..maxAttempts) {
            val statusBody = JSONObject().apply { put("fileTicket", fileTicket) }
            val status = postJsonDebug("$ENDPOINT_V2/image/imageStatus", statusBody, apiKey)
            val statusResp = status.json
            lastInfo = status.info

            if (statusResp != null) {
                apiErrorMessage(statusResp)?.let { return StatusResult(null, it) }
                val statusData = statusResp.optJSONObject("data") ?: statusResp
                val statusCode = statusData.optInt("status", -1)
                if (statusCode == 1) {
                    return StatusResult(statusData, null)
                }
                if (statusCode == 2) {
                    val reason = statusData.optString("failedReason", "")
                    return StatusResult(null, "Binance xử lý thất bại${if (reason.isNotBlank()) ": $reason" else ""}")
                }
            }
            Thread.sleep(1500)
        }
        return StatusResult(null, "Binance xử lý quá lâu, timeout ($lastInfo)")
    }

    // ---------- Upload video ----------

    fun uploadVideoBytes(
        bytes: ByteArray,
        fileName: String,
        apiKey: String,
        contentType: String? = null
    ): UploadResult {
        return try {
            val presignBody = JSONObject().apply {
                put("fileName", fileName)
                put("size", bytes.size)
            }
            val presign = postJsonDebug("$ENDPOINT_V2/video/preSign", presignBody, apiKey)
            val presignResp = presign.json
                ?: return UploadResult(null, "xin presigned URL thất bại (${presign.info})")

            apiErrorMessage(presignResp)?.let { return UploadResult(null, it) }

            val presignData = presignResp.optJSONObject("data") ?: presignResp
            val presignedUrl = presignData.optString("presignedUrl", "")
            val fileTicket = presignData.optString("fileTicket", "")
            if (presignedUrl.isBlank() || fileTicket.isBlank()) {
                return UploadResult(null, "Binance không trả về presigned URL cho video (phản hồi: ${presignResp.toString().take(200)})")
            }

            val effectiveContentType = contentType ?: contentTypeForFileName(fileName)
            val put = putBytesDebug(presignedUrl, bytes, effectiveContentType)
            if (!put.success) {
                return UploadResult(null, "upload video lên S3 thất bại (${put.info})")
            }

            // Đợi Binance xử lý xong video (cùng cơ chế status với ảnh) trước
            // khi coi là sẵn sàng để đăng bài, tránh lỗi "Upload failed" do
            // đăng bài quá sớm lúc video chưa xử lý xong.
            val statusResult = pollFileStatus(fileTicket, apiKey, maxAttempts = 40)
            if (statusResult.value == null) {
                return UploadResult(null, statusResult.error ?: "Binance xử lý video thất bại")
            }

            UploadResult(fileTicket, null)
        } catch (e: Exception) {
            UploadResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- Đăng nội dung khi ảnh/video là link từ xa ----------

    // Dùng chung cho cả luồng chia sẻ bài X qua link (ShareActivity) lẫn luồng
    // tự động lấy bài kênh Telegram (TelegramAutoPostWorker): tự tải file từ
    // link, tự nhận dạng định dạng thật, tự upload lên Binance rồi đăng. Ưu
    // tiên: video > ảnh > chỉ text - Binance Square không cho đăng ảnh và
    // video cùng lúc trong 1 bài.
    fun postRemoteContent(
        context: Context,
        text: String,
        photoUrls: List<String>,
        videoUrl: String?,
        apiKey: String
    ): SquarePostResult {
        if (videoUrl != null) {
            val dl = downloadBytesDebug(videoUrl, accept = "video/mp4,video/*;q=0.9,*/*;q=0.1")
            val bytes = dl.bytes
                ?: return SquarePostResult(false, null, "Không tải được video từ bài viết (${dl.info})")
            val videoContentType = normalizeVideoContentType(dl.contentType)
            val videoExt = extensionForVideoContentType(videoContentType)
            return postVideoBytesFromContext(context, text, bytes, "remote_video.$videoExt", apiKey, videoContentType)
        }

        if (photoUrls.isNotEmpty()) {
            val urls = mutableListOf<String>()
            var lastError = ""
            for ((index, photoUrl) in photoUrls.withIndex()) {
                val dl = downloadBytesDebug(photoUrl, accept = "image/*")
                if (dl.bytes == null) {
                    lastError = dl.info
                    continue
                }
                val imageContentType = normalizeImageContentType(dl.contentType, photoUrl)
                val imageExt = extensionForImageContentType(imageContentType)
                val uploaded = uploadImageBytes(dl.bytes, "remote_image_$index.$imageExt", apiKey, imageContentType)
                if (uploaded.value != null) urls.add(uploaded.value) else lastError = uploaded.error ?: "upload lên Binance thất bại"
            }
            if (urls.isEmpty()) return SquarePostResult(false, null, "Không tải được ảnh từ bài viết ($lastError)")
            return postImages(text, urls, apiKey)
        }

        if (text.isBlank()) {
            return SquarePostResult(false, null, "Không có nội dung để đăng")
        }

        return postText(text, apiKey)
    }

    // Đăng video kèm việc tự tạo ảnh bìa + lấy thời lượng - dùng chung cho cả
    // video chọn từ máy (ShareActivity) lẫn video tải về từ link bài viết
    // (X/Telegram). Cần Context để tạo file tạm trong cacheDir cho
    // MediaMetadataRetriever đọc.
    fun postVideoBytesFromContext(
        context: Context,
        text: String,
        bytes: ByteArray,
        fileName: String,
        apiKey: String,
        contentType: String? = null
    ): SquarePostResult {
        val videoUpload = uploadVideoBytes(bytes, fileName, apiKey, contentType)
        val fileTicket = videoUpload.value
            ?: return SquarePostResult(false, null, "Không tải video lên được (${videoUpload.error})")

        val info = extractVideoInfo(context, bytes)
        val bitmap = info.coverBitmap
            ?: return SquarePostResult(false, null, "Không tạo được ảnh bìa cho video")

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)

        val coverUpload = uploadImageBytes(output.toByteArray(), "video_cover.jpg", apiKey)
        val coverUrl = coverUpload.value
            ?: return SquarePostResult(false, null, "Không tải được ảnh bìa video (${coverUpload.error})")

        return postVideo(text, fileTicket, coverUrl, info.durationSeconds, apiKey)
    }

    private data class VideoInfo(val coverBitmap: Bitmap?, val durationSeconds: Double)

    private fun extractVideoInfo(context: Context, videoBytes: ByteArray): VideoInfo {
        var tempFile: File? = null

        return try {
            tempFile = File.createTempFile("square_video", ".mp4", context.cacheDir)
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

    // Nếu Binance trả về code lỗi kèm message (ngay cả khi HTTP status là 200),
    // lấy ra thông báo lỗi thật thay vì để chỗ gọi tự đoán chung chung.
    private fun apiErrorMessage(resp: JSONObject): String? {
        val code = resp.optString("code", "")
        if (code.isBlank() || code == "000000") return null
        val message = resp.optString("message", "")
        return if (message.isNotBlank()) "Binance lỗi ($code): $message" else "Binance lỗi mã $code"
    }

    // ---------- Lấy nội dung bài X từ link (khi Share Sheet chỉ đưa link) ----------

    // Thử lần lượt nhiều kiểu token cho API syndication của X, giống đúng cách
    // Chrome extension đang làm (extension thử không token, rồi token=x). Ưu
    // tiên token tính toán trước (đã xác nhận lấy đúng text), sau đó thử các
    // kiểu dự phòng để tăng khả năng lấy được ảnh/video.
    fun fetchTweetContent(tweetUrl: String): TweetContent? {
        val idMatch = Regex("status/(\\d+)").find(tweetUrl) ?: return null
        val tweetId = idMatch.groupValues[1]

        val candidateTokens = listOf(syndicationToken(tweetId), null, "x")

        for (token in candidateTokens) {
            val json = fetchSyndicationJson(tweetId, token) ?: continue

            val text = json.optString("text", json.optString("full_text", ""))

            val photos = mutableListOf<String>()
            json.optJSONArray("photos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val url = arr.optJSONObject(i)?.optString("url")
                    if (!url.isNullOrBlank()) photos.add(applyOrigSize(url))
                }
            }

            var videoUrl: String? = null
            var videoPoster: String? = null

            json.optJSONObject("video")?.let { video ->
                val poster = video.optString("poster", "")
                if (poster.isNotBlank()) videoPoster = applyOrigSize(poster)

                var bestBitrate = -1
                video.optJSONArray("variants")?.let { variants ->
                    for (i in 0 until variants.length()) {
                        val v = variants.optJSONObject(i) ?: continue
                        val type = v.optString("type", "")
                        if (type.contains("mp4")) {
                            val bitrate = v.optInt("bitrate", 0)
                            if (bitrate >= bestBitrate) {
                                bestBitrate = bitrate
                                videoUrl = v.optString("src", "")
                            }
                        }
                    }
                }
            }

            // Nếu lần thử này không lấy được gì hữu ích thì thử token khác
            // trước khi bỏ cuộc.
            if (text.isBlank() && photos.isEmpty() && videoUrl.isNullOrBlank()) continue

            return TweetContent(
                text = text,
                photoUrls = photos,
                videoUrl = videoUrl,
                videoPosterUrl = videoPoster
            )
        }

        return null
    }

    private fun fetchSyndicationJson(tweetId: String, token: String?): JSONObject? {
        return try {
            val base = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId"
            val urlString = if (token.isNullOrEmpty()) base else "$base&token=$token"

            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode !in 200..299) return null

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(responseText)
        } catch (e: Exception) {
            null
        }
    }

    // Ép URL ảnh/poster của X về bản chất lượng gốc, giống hệt cách Chrome
    // extension đang làm: u.searchParams.set('name', 'orig').
    private fun applyOrigSize(url: String): String {
        return try {
            val u = URI(url)
            val query = (u.query ?: "").split("&").filter { it.isNotBlank() }
            val params = LinkedHashMap<String, String>()
            for (pair in query) {
                val idx = pair.indexOf('=')
                if (idx == -1) continue
                params[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
            params["name"] = "orig"
            val newQuery = params.entries.joinToString("&") { (k, v) -> "$k=$v" }
            "${url.substringBefore("?")}?$newQuery"
        } catch (e: Exception) {
            url
        }
    }

    // Cách lấy nội dung dự phòng, dùng oEmbed công khai (chỉ có text, không có ảnh/video)
    fun fetchTweetText(tweetUrl: String): String? {
        val oembedUrlString = "https://publish.twitter.com/oembed?url=" +
            URLEncoder.encode(tweetUrl, "UTF-8") + "&omit_script=true"

        return try {
            val connection = URL(oembedUrlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode !in 200..299) return null

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(responseText)
            plainTextFromTweetHtml(json.optString("html", ""))
        } catch (e: Exception) {
            null
        }
    }

    data class DownloadResult(val bytes: ByteArray?, val info: String, val contentType: String? = null)

    // Tải ảnh/video từ máy chủ của X (pbs.twimg.com, video.twimg.com...).
    // Mô phỏng đúng theo cách Chrome extension đã chạy tốt: User-Agent trình
    // duyệt máy tính thật, KHÔNG set Referer giả, Accept header đúng loại media.
    // Đồng thời đọc luôn Content-Type thật từ response, để khi upload lên
    // Binance dùng đúng định dạng (tránh lệch định dạng gây upload thất bại).
    fun downloadBytesDebug(urlString: String, accept: String = "*/*"): DownloadResult {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            connection.setRequestProperty("Accept", accept)
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20000
            connection.readTimeout = 30000

            val code = connection.responseCode
            if (code !in 200..299) {
                return DownloadResult(null, "HTTP $code")
            }

            val rawContentType = connection.contentType?.substringBefore(";")?.trim()?.lowercase()

            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                return DownloadResult(null, "0 bytes")
            }

            DownloadResult(bytes, "OK (${bytes.size} bytes)", rawContentType)
        } catch (e: Exception) {
            DownloadResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    fun downloadBytes(urlString: String): ByteArray? = downloadBytesDebug(urlString).bytes

    // Chuẩn hoá Content-Type ảnh về 1 trong các loại Binance/S3 chấp nhận,
    // dự phòng bằng phần đuôi/format trong chính URL khi server không trả
    // Content-Type rõ ràng.
    fun normalizeImageContentType(contentType: String?, url: String): String {
        val known = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        if (contentType != null && known.contains(contentType)) return contentType

        val lowerUrl = url.substringBefore("?").lowercase()
        return when {
            lowerUrl.endsWith(".png") -> "image/png"
            lowerUrl.endsWith(".gif") -> "image/gif"
            lowerUrl.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    fun extensionForImageContentType(contentType: String): String {
        return when (contentType) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }

    // Video từ X thường là mp4, nhưng đôi khi (vd GIF động dạng video) là webm.
    fun normalizeVideoContentType(contentType: String?): String {
        return if (contentType == "video/webm") "video/webm" else "video/mp4"
    }

    fun extensionForVideoContentType(contentType: String): String {
        return if (contentType == "video/webm") "webm" else "mp4"
    }

    // ---------- Helpers ----------

    private fun postJson(urlString: String, body: JSONObject, apiKey: String): JSONObject? {
        return postJsonDebug(urlString, body, apiKey).json
    }

    private data class JsonDebugResult(val json: JSONObject?, val info: String)

    // Giống postJson nhưng luôn trả kèm mô tả ngắn gọn về mã HTTP/nội dung lỗi
    // (nếu có), để hiện lên Toast giúp chẩn đoán chính xác chỗ bị chặn.
    private fun postJsonDebug(urlString: String, body: JSONObject, apiKey: String): JsonDebugResult {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Square-OpenAPI-Key", apiKey)
            connection.setRequestProperty("clienttype", "binanceSkill")
            connection.connectTimeout = 15000
            connection.readTimeout = 20000

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(body.toString())
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return JsonDebugResult(null, "HTTP $code: ${responseText.take(200)}")
            }

            JsonDebugResult(JSONObject(responseText), "OK")
        } catch (e: Exception) {
            JsonDebugResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    private data class PutDebugResult(val success: Boolean, val info: String)

    private fun putBytesDebug(urlString: String, bytes: ByteArray, contentType: String): PutDebugResult {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            if (code in 200..299) return PutDebugResult(true, "OK")

            val errorText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            PutDebugResult(false, "HTTP $code: ${errorText.take(200)}")
        } catch (e: Exception) {
            PutDebugResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun contentTypeForFileName(fileName: String): String {
        return when {
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".webp", true) -> "image/webp"
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mov", true) -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }

    // Mô phỏng lại công thức tính token cho API syndication của X
    // (giống cách các thư viện mã nguồn mở như react-tweet đang dùng).
    private fun syndicationToken(tweetId: String): String {
        val value = (tweetId.toDouble() / 1e15) * Math.PI
        return doubleToBase36(value).replace(Regex("(0+|\\.)"), "")
    }

    private fun doubleToBase36(value: Double): String {
        val negative = value < 0
        val v = kotlin.math.abs(value)
        val intPart = v.toLong()
        val frac = v - intPart

        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(intPart.toString(36))

        if (frac > 1e-12) {
            sb.append('.')
            var f = frac
            var i = 0
            while (f > 1e-12 && i < 20) {
                f *= 36
                val digit = f.toInt()
                sb.append(Character.forDigit(digit, 36))
                f -= digit
                i++
            }
        }

        return sb.toString()
    }

    private fun plainTextFromTweetHtml(html: String): String? {
        val pStart = html.indexOf("<p")
        if (pStart == -1) return null

        val pOpenEnd = html.indexOf(">", pStart)
        if (pOpenEnd == -1) return null

        val pClose = html.indexOf("</p>", pOpenEnd)
        if (pClose == -1) return null

        var text = html.substring(pOpenEnd + 1, pClose)
        text = text.replace("<br>", "\n").replace("<br/>", "\n")
        text = text.replace(Regex("<[^>]+>"), "")

        val entities = mapOf(
            "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–",
            "&nbsp;" to " ", "&hellip;" to "…"
        )

        for ((entity, character) in entities) {
            text = text.replace(entity, character)
        }

        return text.trim()
    }
}
