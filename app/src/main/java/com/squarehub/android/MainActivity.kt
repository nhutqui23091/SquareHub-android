package com.squarehub.android

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val apiKeyField = findViewById<EditText>(R.id.apiKeyField)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        val prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        apiKeyField.setText(prefs.getString(Config.API_KEY_PREF, ""))

        saveButton.setOnClickListener {
            val key = apiKeyField.text.toString().trim()
            prefs.edit().putString(Config.API_KEY_PREF, key).apply()
            statusText.text = "Đã lưu"
            Toast.makeText(this, "Đã lưu API key", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.openTelegramButton).setOnClickListener {
            startActivity(Intent(this, TelegramActivity::class.java))
        }

        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật lại số liệu mỗi lần quay lại màn hình này, vd sau khi vừa
        // đăng xong 1 bài từ Share Sheet hoặc quay lại từ màn hình Telegram.
        refreshStats()
    }

    private fun refreshStats() {
        val stats = PostStats.getStats(this)
        findViewById<TextView>(R.id.totalCountText).text = stats.total.toString()
        findViewById<TextView>(R.id.todayCountText).text = stats.today.toString()
    }
}
