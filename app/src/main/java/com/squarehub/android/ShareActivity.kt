package com.squarehub.android

import android.content.Intent
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
import java.util.regex.Pattern

class ShareActivity : AppCompatActivity() {

    private var sharedText: String = ""
    private var sharedUrl: String? = null

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

    private fun handleIncomingIntent() {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
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
            previewText.text = "Đang lấy nội dung bài viết..."

            val url = sharedUrl!!

            CoroutineScope(Dispatchers.Main).launch {
                val tweetText = withContext(Dispatchers.IO) {
                    SquareApi.fetchTweetText(url)
                }

                if (!tweetText.isNullOrBlank()) {
                    sharedText = "$tweetText\n\n$url"
                }

                previewText.text = sharedText
            }
        } else {
            previewText.text = sharedText.ifBlank { "Không có nội dung để đăng." }
        }
    }

    private fun post() {
        val prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(Config.API_KEY_PREF, "") ?: ""

        if (apiKey.isBlank()) {
            previewText.text = "⚠️ Chưa có API key. Mở app SquareHub để lưu key."
            return
        }

        if (sharedText.isBlank()) {
            return
        }

        postButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                SquareApi.postText(sharedText, apiKey)
            }

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
}
