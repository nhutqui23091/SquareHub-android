package com.squarehub.android

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

// Endpoint và header lấy theo đúng "Square OpenAPI" chính thức của Binance
// (xem: binance.com/vi/skills/detail/binance/square-post)
object SquareApi {

    private const val ENDPOINT =
        "https://www.binance.com/bapi/composite/v1/public/pgc/openApi/content/add"

    fun postText(text: String, apiKey: String): SquarePostResult {
        return try {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Square-OpenAPI-Key", apiKey)
            connection.setRequestProperty("clienttype", "binanceSkill")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val body = JSONObject().apply {
                put("bodyTextOnly", text)
            }.toString()

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(body)
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return SquarePostResult(false, null, "HTTP error ($code)")
            }

            val json = JSONObject(responseText)

            if (json.optString("code") == "000000") {
                val id = json.optJSONObject("data")?.opt("id")?.toString()
                SquarePostResult(true, id, "Đã đăng lên Binance Square")
            } else {
                SquarePostResult(false, null, json.optString("message", "Binance API error"))
            }
        } catch (e: Exception) {
            SquarePostResult(false, null, e.message ?: "Lỗi không xác định")
        }
    }

    // X (Twitter) trên Android nhiều lúc chỉ đưa qua link, không kèm nội
    // dung chữ. Hàm này gọi oEmbed công khai của X để lấy nội dung thật.
    fun fetchTweetText(tweetUrl: String): String? {
        return try {
            val encoded = URLEncoder.encode(tweetUrl, "UTF-8")
            val connection = URL(
                "https://publish.twitter.com/oembed?url=$encoded&omit_script=true"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val code = connection.responseCode
            if (code !in 200..299) return null

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }

            val json = JSONObject(responseText)
            plainTextFromTweetHtml(json.optString("html", ""))
        } catch (e: Exception) {
            null
        }
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
