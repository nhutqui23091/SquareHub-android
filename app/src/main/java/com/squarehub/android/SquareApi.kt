package com.squarehub.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
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
object SquareApi {

    private const val ENDPOINT_V1 = "https://www.binance.com/bapi/composite/v1/public/pgc/openApi"
    private const val ENDPOINT_V2 = "https://www.binance.com/bapi/composite/v2/public/pgc/openApi"

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

    // ---------- Upload ảnh ----------

    fun uploadImageBytes(bytes: ByteArray, fileName: String, apiKey: String): String? {
        return try {
            val presignBody = JSONObject().apply { put("imageName", fileName) }
            val presignResp = postJson("$ENDPOINT_V2/image/presignedUrl", presignBody, apiKey)
                ?: return null

            val presignedUrl = presignResp.optString("presignedUrl", "")
            val fileTicket = presignResp.optString("fileTicket", "")
            if (presignedUrl.isBlank() || fileTicket.isBlank()) return null

            if (!putBytes(presignedUrl, bytes, contentTypeForFileName(fileName))) return null

            var imageUrl: String? = null
            for (attempt in 1..10) {
                val statusBody = JSONObject().apply { put("fileTicket", fileTicket) }
                val statusResp = postJson("$ENDPOINT_V2/image/imageStatus", statusBody, apiKey)
                val url = statusResp?.optString("imageUrl", "")
                if (!url.isNullOrBlank()) {
                    imageUrl = url
                    break
                }
                Thread.sleep(1000)
            }

            imageUrl
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Upload video ----------

    fun uploadVideoBytes(bytes: ByteArray, fileName: String, apiKey: String): String? {
        return try {
            val presignBody = JSONObject().apply {
                put("fileName", fileName)
                put("size", bytes.size)
            }
            val presignResp = postJson("$ENDPOINT_V2/video/preSign", presignBody, apiKey)
                ?: return null

            val presignedUrl = presignResp.optString("presignedUrl", "")
            val fileTicket = presignResp.optString("fileTicket", "")
            if (presignedUrl.isBlank() || fileTicket.isBlank()) return null

            if (!putBytes(presignedUrl, bytes, contentTypeForFileName(fileName))) return null

            fileTicket
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Lấy nội dung bài X từ link (khi Share Sheet chỉ đưa link) ----------

    fun fetchTweetContent(tweetUrl: String): TweetContent? {
        val idMatch = Regex("status/(\\d+)").find(tweetUrl) ?: return null
        val tweetId = idMatch.groupValues[1]
        val token = syndicationToken(tweetId)

        return try {
            val connection = URL(
                "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=$token"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            )
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode !in 200..299) return null

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(responseText)

            val text = json.optString("text", json.optString("full_text", ""))

            val photos = mutableListOf<String>()
            json.optJSONArray("photos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val url = arr.optJSONObject(i)?.optString("url")
                    if (!url.isNullOrBlank()) photos.add(url)
                }
            }

            var videoUrl: String? = null
            var videoPoster: String? = null

            json.optJSONObject("video")?.let { video ->
                val poster = video.optString("poster", "")
                if (poster.isNotBlank()) videoPoster = poster

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

            TweetContent(
                text = text,
                photoUrls = photos,
                videoUrl = videoUrl,
                videoPosterUrl = videoPoster
            )
        } catch (e: Exception) {
            null
        }
    }

    // Cách lấy nội dung dự phòng, dùng oEmbed công khai (chỉ có text, không có ảnh/video)
    fun fetchTweetText(tweetUrl: String): String? {
        val oembedUrlString = "https://publish.twitter.com/oembed?url=" +
            URLEncoder.encode(tweetUrl, "UTF-8") + "&omit_script=true"

        return try {
            val connection = URL(oembedUrlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
            )
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

    fun downloadBytes(urlString: String): ByteArray? {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Helpers ----------

    private fun postJson(urlString: String, body: JSONObject, apiKey: String): JSONObject? {
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
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return null

            if (code !in 200..299) return null

            JSONObject(responseText)
        } catch (e: Exception) {
            null
        }
    }

    private fun putBytes(urlString: String, bytes: ByteArray, contentType: String): Boolean {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            false
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
