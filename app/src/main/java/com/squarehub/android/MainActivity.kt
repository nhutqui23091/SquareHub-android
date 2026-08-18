package com.squarehub.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var telegramMasterSwitch: Switch
    private lateinit var telegramChannelField: EditText
    private lateinit var telegramChannelListContainer: LinearLayout

    private val notificationPermissionRequestCode = 1001

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

        telegramMasterSwitch = findViewById(R.id.telegramMasterSwitch)
        telegramChannelField = findViewById(R.id.telegramChannelField)
        telegramChannelListContainer = findViewById(R.id.telegramChannelListContainer)

        val masterEnabled = TelegramChannels.isMasterEnabled(this)
        telegramMasterSwitch.isChecked = masterEnabled
        // Nếu trước đó đã bật rồi thì đảm bảo lịch WorkManager vẫn còn - đề
        // phòng trường hợp hệ thống/OEM đã xoá mất lịch chạy nền.
        if (masterEnabled) TelegramScheduler.schedule(this)

        telegramMasterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestNotificationPermissionIfNeeded()
                TelegramChannels.setMasterEnabled(this, true)
                TelegramScheduler.schedule(this)
                Toast.makeText(this, "Đã bật tự động đăng từ Telegram", Toast.LENGTH_SHORT).show()
            } else {
                TelegramChannels.setMasterEnabled(this, false)
                TelegramScheduler.cancel(this)
                Toast.makeText(this, "Đã tắt tự động đăng từ Telegram", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.addChannelButton).setOnClickListener {
            val input = telegramChannelField.text.toString()
            if (TelegramChannels.addChannel(this, input)) {
                telegramChannelField.setText("")
                refreshChannelList()
            } else {
                Toast.makeText(
                    this,
                    "Tên kênh không hợp lệ hoặc đã có trong danh sách",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        refreshStats()
        refreshChannelList()
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật lại số liệu mỗi lần quay lại màn hình này, vd sau khi vừa
        // đăng xong 1 bài từ Share Sheet rồi mở lại app.
        refreshStats()
    }

    private fun refreshStats() {
        val stats = PostStats.getStats(this)
        findViewById<TextView>(R.id.totalCountText).text = stats.total.toString()
        findViewById<TextView>(R.id.todayCountText).text = stats.today.toString()
    }

    // Vẽ lại danh sách kênh Telegram mỗi khi có thêm/xoá kênh - danh sách
    // thường chỉ vài kênh nên vẽ lại toàn bộ cho đơn giản, không cần
    // RecyclerView.
    private fun refreshChannelList() {
        telegramChannelListContainer.removeAllViews()

        val channels = TelegramChannels.getChannels(this)
        for (channel in channels) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }

            val nameText = TextView(this).apply {
                text = "@${channel.username}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val enableSwitch = Switch(this).apply {
                isChecked = channel.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    TelegramChannels.setChannelEnabled(this@MainActivity, channel.username, isChecked)
                }
            }

            val removeButton = Button(this).apply {
                text = "Xoá"
                setOnClickListener {
                    TelegramChannels.removeChannel(this@MainActivity, channel.username)
                    refreshChannelList()
                }
            }

            row.addView(nameText)
            row.addView(enableSwitch)
            row.addView(removeButton)
            telegramChannelListContainer.addView(row)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    notificationPermissionRequestCode
                )
            }
        }
    }
}
