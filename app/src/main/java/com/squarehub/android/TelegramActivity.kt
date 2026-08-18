package com.squarehub.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Màn hình riêng để quản lý tính năng tự động đăng bài từ Telegram (bật/tắt,
// danh sách kênh, quét thủ công để test, lịch sử quét) - tách khỏi màn hình
// chính (API key) cho gọn, vì đây là 1 tính năng nâng cao/tuỳ chọn.
class TelegramActivity : AppCompatActivity() {

    private lateinit var telegramMasterSwitch: Switch
    private lateinit var telegramChannelField: EditText
    private lateinit var telegramChannelListContainer: LinearLayout
    private lateinit var telegramScanLogContainer: LinearLayout
    private lateinit var scanNowButton: Button

    private val notificationPermissionRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram)

        telegramMasterSwitch = findViewById(R.id.telegramMasterSwitch)
        telegramChannelField = findViewById(R.id.telegramChannelField)
        telegramChannelListContainer = findViewById(R.id.telegramChannelListContainer)
        telegramScanLogContainer = findViewById(R.id.telegramScanLogContainer)
        scanNowButton = findViewById(R.id.scanNowButton)

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

        scanNowButton.setOnClickListener { runManualScan() }

        findViewById<Button>(R.id.clearScanLogButton).setOnClickListener {
            TelegramScanLog.clear(this)
            refreshScanLog()
        }

        refreshChannelList()
        refreshScanLog()
    }

    override fun onResume() {
        super.onResume()
        refreshChannelList()
        refreshScanLog()
    }

    // Chạy ngay 1 lần kiểm tra kênh (không đợi lịch 15 phút), để test cho
    // nhanh. Bỏ qua điều kiện "đã bật công tắc tổng" vì mục đích ở đây là
    // test xem đọc kênh có thành công không, không phải chạy như production.
    private fun runManualScan() {
        val prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(Config.API_KEY_PREF, "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Chưa có API key. Mở màn hình chính để lưu key trước.", Toast.LENGTH_LONG).show()
            return
        }
        if (TelegramChannels.getChannels(this).none { it.enabled }) {
            Toast.makeText(this, "Chưa có kênh nào đang bật để quét.", Toast.LENGTH_LONG).show()
            return
        }

        scanNowButton.isEnabled = false
        scanNowButton.text = "Đang quét..."
        Toast.makeText(this, "Đang quét...", Toast.LENGTH_SHORT).show()

        val request = OneTimeWorkRequestBuilder<TelegramAutoPostWorker>()
            .setInputData(workDataOf(TelegramAutoPostWorker.INPUT_FORCE to true))
            .build()

        WorkManager.getInstance(this).enqueue(request)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info != null && info.state.isFinished) {
                scanNowButton.isEnabled = true
                scanNowButton.text = "Quét ngay (test)"
                refreshChannelList()
                refreshScanLog()
                Toast.makeText(this, "Quét xong, xem kết quả ở Lịch sử quét bên dưới", Toast.LENGTH_SHORT).show()
            }
        }
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
                    TelegramChannels.setChannelEnabled(this@TelegramActivity, channel.username, isChecked)
                }
            }

            val removeButton = Button(this).apply {
                text = "Xoá"
                setOnClickListener {
                    TelegramChannels.removeChannel(this@TelegramActivity, channel.username)
                    refreshChannelList()
                }
            }

            row.addView(nameText)
            row.addView(enableSwitch)
            row.addView(removeButton)
            telegramChannelListContainer.addView(row)
        }
    }

    // Vẽ lại lịch sử quét Telegram (mới nhất trước) - mỗi lần quét hiện tóm
    // tắt (kiểm tra mấy kênh, mấy bài mới), kèm từng bài đã đăng có thành
    // công không và vài chữ đầu nội dung, để người dùng tự xác nhận tính
    // năng có đang thật sự chạy hay không mà không cần đoán mò.
    private fun refreshScanLog() {
        telegramScanLogContainer.removeAllViews()

        val scans = TelegramScanLog.getScans(this).take(30)

        if (scans.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Chưa có lần quét nào."
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }
            telegramScanLogContainer.addView(empty)
            return
        }

        val timeFormat = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault())

        for (scan in scans) {
            val timeText = timeFormat.format(Date(scan.timestampMs))
            val summary = if (scan.posts.isEmpty()) {
                "$timeText – kiểm tra ${scan.channelsChecked} kênh, không có bài mới"
            } else {
                val successCount = scan.posts.count { it.success }
                "$timeText – kiểm tra ${scan.channelsChecked} kênh, ${scan.posts.size} bài ($successCount thành công)"
            }

            val summaryText = TextView(this).apply {
                text = summary
                textSize = 12f
                setTextColor(Color.parseColor("#444444"))
                setPadding(0, 10, 0, 2)
            }
            telegramScanLogContainer.addView(summaryText)

            for (post in scan.posts) {
                val icon = if (post.success) "✅" else "❌"
                val detail = if (post.success) {
                    "$icon @${post.channelUsername}: ${post.snippet}"
                } else {
                    val base = post.snippet.ifBlank { "(không có nội dung)" }
                    "$icon @${post.channelUsername}: $base — ${post.message}"
                }

                val detailText = TextView(this).apply {
                    text = detail
                    textSize = 12f
                    setPadding(16, 2, 0, 2)
                    setTextColor(Color.parseColor(if (post.success) "#2e7d32" else "#c62828"))
                }
                telegramScanLogContainer.addView(detailText)
            }
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
