package com.squarehub.android

import java.util.regex.Pattern

// Dùng chung cho cả luồng chia sẻ thủ công (ShareActivity) và luồng tự động
// kiểm tra kênh Telegram (TelegramAutoPostWorker), để 2 nơi này xử lý văn
// bản/ID bài viết giống hệt nhau, không lệch nhau.
object TextCleaner {

    // Lấy 1 ID định danh cho bài viết (từ X hoặc kênh Telegram) từ đoạn text
    // bất kỳ, dùng để chống đăng trùng. X: link .../status/12345. Telegram
    // (kênh công khai): link t.me/tenkenh/12345 - ghép tên kênh + số bài để
    // ra 1 ID duy nhất, vì cùng 1 số bài nhưng khác kênh vẫn là bài khác.
    fun extractPostId(text: String): String? {
        val tweetMatcher = Pattern.compile("status/(\\d+)").matcher(text)
        if (tweetMatcher.find()) return "x:" + tweetMatcher.group(1)

        val telegramMatcher = Pattern.compile("t\\.me/([A-Za-z0-9_]+)/(\\d+)").matcher(text)
        if (telegramMatcher.find()) return "tg:" + telegramMatcher.group(1) + ":" + telegramMatcher.group(2)

        return null
    }

    // ID chống trùng dùng riêng cho bài lấy tự động từ Telegram (đã biết
    // chắc tên kênh + số bài, không cần dò trong text).
    fun telegramPostId(channelUsername: String, postId: Long): String =
        "tg:$channelUsername:$postId"

    // Dọn các thứ dư thừa hay bị dính theo khi share bài từ X hoặc kênh
    // Telegram, để nội dung đăng lên Square chỉ còn đúng phần chữ thật:
    // - Link rút gọn t.co / link t.me (kênh Telegram) / link twitter.com,x.com
    // - Cụm "Read More" (kênh Telegram hay chèn cuối bài, kèm link bài gốc)
    // - Dòng "By <tên> | @kenh" (chữ ký tác giả/kênh Telegram hay chèn cuối bài)
    fun cleanText(text: String): String {
        var cleaned = text.replace(Regex("https?://t\\.co/\\S+"), "")
        cleaned = cleaned.replace(Regex("https?://(www\\.)?(twitter|x)\\.com/\\S+"), "")
        cleaned = cleaned.replace(Regex("https?://t\\.me/\\S+"), "")

        // "... long positions." – Read More  ->  "... long positions."
        cleaned = cleaned.replace(Regex("[\\s]*[-–—]?\\s*Read [Mm]ore\\.?", RegexOption.IGNORE_CASE), "")

        // Dòng kiểu "By G a a h | @cryptoquant_official"
        cleaned = cleaned.replace(Regex("(?m)^\\s*By\\s+.*@\\S+\\s*$"), "")

        // Dọn khoảng trắng/dòng trống thừa ra do vừa xoá link/chữ ký.
        cleaned = cleaned.replace(Regex("[ \\t]+\\n"), "\n")
        cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n")
        return cleaned.trim()
    }

    // Các câu thông báo hệ thống Telegram tự sinh ra trong kênh (không phải
    // bài đăng thật của chủ kênh). Những dòng này KHÔNG được đăng lên Square.
    private val SERVICE_MESSAGE_PATTERNS = listOf(
        "channel created",
        "group created",
        "channel photo updated",
        "channel photo removed",
        "channel name was changed",
        "messages in this channel will be automatically deleted",
        "messages in this group will be automatically deleted",
        "pinned a message",
        "pinned «",
        "joined the group",
        "joined the channel",
        "video chat started",
        "video chat ended",
        "voice chat started",
        "voice chat ended",
        "this channel is now public",
        "channel converted to"
    )

    // Nhận diện thông báo hệ thống của Telegram để bỏ qua, tránh đăng nhầm
    // mấy câu như "Channel created" hay "Messages in this channel will be
    // automatically deleted after 1 month" lên Binance Square.
    fun isTelegramServiceMessage(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return false
        return SERVICE_MESSAGE_PATTERNS.any { normalized.contains(it) }
    }

    // Chuyển 1 đoạn HTML nhỏ (thẻ <br>, <a>, <b>, <span>...) về text thuần,
    // dùng khi đọc nội dung bài viết từ trang t.me/s/<kenh>.
    fun htmlFragmentToPlainText(html: String): String {
        var text = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
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
