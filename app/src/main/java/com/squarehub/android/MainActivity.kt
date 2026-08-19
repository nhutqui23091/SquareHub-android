package com.squarehub.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

// Màn hình chính gồm 3 tab, chuyển qua lại bằng thanh tab dưới cùng:
//  1. Trang chủ  - thống kê + API key
//  2. Telegram   - bật/tắt tự động, quản lý kênh, quét thủ công
//  3. Lịch sử    - lịch sử các lần quét nền
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAB_HOME = 0
        const val TAB_TELEGRAM = 1
        const val TAB_HISTORY = 2

        const val COLOR_TAB_ACTIVE = "#F0B90B"
        const val COLOR_TAB_INACTIVE = "#9A9A9A"

        const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }

    private lateinit var headerTitle: TextView

    private lateinit var tabHomeContent: ScrollView
    private lateinit var tabTelegramContent: ScrollView
    private lateinit var tabHistoryContent: ScrollView

    private lateinit var tabHomeIcon: ImageView
    private lateinit var tabTelegramIcon: ImageView
    private lateinit var tabHistoryIcon: ImageView

    private lateinit var tabHomeLabel: TextView
    private lateinit var tabTelegramLabel: TextView
    private lateinit var tabHistoryLabel: TextView

    private lateinit var telegramMasterSwitch: Switch
    private lateinit var telegramChannelField: EditText
    private lateinit var telegramChannelListContainer: LinearLayout
    private lateinit var telegramScanLogContainer: LinearLayout
    private lateinit var scanNowButton: Button

    private var currentTab = TAB_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setUpTabBar()
        setUpHomeTab()
        setUpTelegramTab()
        setUpHistoryTab()

        selectTab(TAB_HOME)
        refreshStats()
        refreshChannelList()
        refreshScanLog()
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật lại mỗi lần quay lại app, vd sau khi vừa đăng 1 bài từ
        // Share Sheet, hoặc sau khi có lần quét Telegram chạy nền.
        refreshStats()
        refreshChannelList()
        refreshScanLog()
    }

    private fun bindViews() {
        headerTitle = findViewById(R.id.headerTitle)

        tabHomeContent = findViewById(R.id.tabHomeContent)
        tabTelegramContent = findViewById(R.id.tabTelegramContent)
        tabHistoryContent = findViewById(R.id.tabHistoryContent)

        tabHomeIcon = findViewById(R.id.tabHomeIcon)
        tabTelegramIcon = findViewById(R.id.tabTelegramIcon)
        tabHistoryIcon = findViewById(R.id.tabHistoryIcon)

        tabHomeLabel = findViewById(R.id.tabHomeLabel)
        tabTelegramLabel = findViewById(R.id.tabTelegramLabel)
        tabHistoryLabel = findViewById(R.id.tabHistoryLabel)

        telegramMasterSwitch = findViewById(R.id.telegramMasterSwitch)
        telegramChannelField = findViewById(R.id.telegramChannelField)
        telegramChannelListContainer = findViewById(R.id.telegramChannelListContainer)
        telegramScanLogContainer = findViewById(R.id.telegramScanLogContainer)
        scanNowButton = findViewById(R.id.scanNowButton)
    }

    // ---------- Thanh tab dưới cùng ----------

    private fun setUpTabBar() {
        findViewById<LinearLayout>(R.id.tabHomeButton).setOnClickListener { selectTab(TAB_HOME) }
        findViewById<LinearLayout>(R.id.tabTelegramButton).setOnClickListener { selectTab(TAB_TELEGRAM) }
        findViewById<LinearLayout>(R.id.tabHistoryButton).setOnClickListener { selectTab(TAB_HISTORY) }
    }

    private fun selectTab(tab: Int) {
        currentTab = tab

        tabHomeContent.visibility = if (tab == TAB_HOME) View.VISIBLE else View.GONE
        tabTelegramContent.visibility = if (tab == TAB_TELEGRAM) View.VISIBLE else View.GONE
        tabHistoryContent.visibility = if (tab == TAB_HISTORY) View.VISIBLE else View.GONE

        headerTitle.text = when (tab) {
            TAB_TELEGRAM -> "Tự động Telegram"
            TAB_HISTORY -> "Lịch sử quét"
            else -> "SquareHub"
        }

        paintTab(tabHomeIcon, tabHomeLabel, tab == TAB_HOME)
        paintTab(tabTelegramIcon, tabTelegramLabel, tab == TAB_TELEGRAM)
        paintTab(tabHistoryIcon, tabHistoryLabel, tab == TAB_HISTORY)

        // Vào tab nào thì làm mới đúng dữ liệu của tab đó, để số liệu/lịch sử
        // luôn là mới nhất ngay khi người dùng chuyển sang xem.
        when (tab) {
            TAB_HOME -> refreshStats()
            TAB_TELEGRAM -> refreshChannelList()
            TAB_HISTORY -> refreshScanLog()
        }
    }

    private fun paintTab(icon: ImageView, label: TextView, active: Boolean) {
        val color = Color.parseColor(if (active) COLOR_TAB_ACTIVE else COLOR_TAB_INACTIVE)
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    // ---------- Tab 1: Trang chủ ----------

    private fun setUpHomeTab() {
        val apiKeyField = findViewById<EditText>(R.id.apiKeyField)
        val statusText = findViewById<TextView>(R.id.statusText)

        val prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        apiKeyField.setText(prefs.getString(Config.API_KEY_PREF, ""))

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val key = apiKeyField.text.toString().trim()
            prefs.edit().putString(Config.API_KEY_PREF, key).apply()
            statusText.text = "Đã lưu"
            Toast.makeText(this, "Đã lưu API key", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStats() {
        val stats = PostStats.getStats(this)
        findViewById<TextView>(R.id.totalCountText).text = stats.total.toString()
        findViewById<TextView>(R.id.todayCountText).text = stats.today.toString()
    }

    // ---------- Tab 2: Telegram ----------

    private fun setUpTelegramTab() {
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
    }

    // Chạy ngay 1 lần kiểm tra kênh (không đợi lịch 15 phút), để test cho
    // nhanh. Bỏ qua điều kiện "đã bật công tắc tổng" vì mục đích ở đây là
    // test xem đọc kênh có ra bài không, không phải chạy như lịch nền thật.
    private fun runManualScan() {
        val prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(Config.API_KEY_PREF, "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Chưa có API key. Sang tab Trang chủ để lưu key trước.", Toast.LENGTH_LONG).show()
            return
        }
        if (TelegramChannels.getChannels(this).none { it.enabled }) {
            Toast.makeText(this, "Chưa có kênh nào đang bật để quét.", Toast.LENGTH_LONG).show()
            return
        }

        scanNowButton.isEnabled = false
        scanNowButton.text = "Đang quét..."

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
                refreshStats()
                Toast.makeText(this, "Quét xong - xem kết quả ở tab Lịch sử", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Vẽ lại danh sách kênh Telegram mỗi khi có thêm/xoá kênh - danh sách
    // thường chỉ vài kênh nên vẽ lại toàn bộ cho đơn giản, không cần
    // RecyclerView.
    private fun refreshChannelList() {
        telegramChannelListContainer.removeAllViews()

        val channels = TelegramChannels.getChannels(this)

        if (channels.isEmpty()) {
            telegramChannelListContainer.addView(
                TextView(this).apply {
                    text = "Chưa thêm kênh nào."
                    textSize = 12f
                    setTextColor(Color.parseColor("#9A9A9A"))
                    setPadding(0, 10, 0, 4)
                }
            )
            return
        }

        for (channel in channels) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }

            val nameText = TextView(this).apply {
                text = "@${channel.username}"
                textSize = 14f
                setTextColor(Color.parseColor("#1A1A1A"))
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

    // ---------- Tab 3: Lịch sử ----------

    private fun setUpHistoryTab() {
        findViewById<Button>(R.id.clearScanLogButton).setOnClickListener {
            TelegramScanLog.clear(this)
            refreshScanLog()
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
            telegramScanLogContainer.addView(
                TextView(this).apply {
                    text = "Chưa có lần quét nào."
                    textSize = 12f
                    setTextColor(Color.parseColor("#9A9A9A"))
                    setPadding(0, 10, 0, 4)
                }
            )
            return
        }

        val timeFormat = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault())

        for (scan in scans) {
            val timeText = timeFormat.format(Date(scan.timestampMs))

            // Chỉ đếm là "bài" khi thật sự có bài viết được xử lý; các dòng
            // lỗi mức kênh/mạng chỉ đếm riêng là "lỗi", không gọi là bài.
            val postEntries = scan.posts.filter { it.isPostAttempt }
            val noteEntries = scan.posts.filter { !it.isPostAttempt }

            val summary = buildString {
                append("$timeText – kiểm tra ${scan.channelsChecked} kênh")
                if (postEntries.isEmpty()) {
                    append(", không có bài mới")
                } else {
                    val successCount = postEntries.count { it.success }
                    append(", ${postEntries.size} bài ($successCount thành công)")
                }
                if (noteEntries.isNotEmpty()) append(", ${noteEntries.size} lỗi")
            }

            telegramScanLogContainer.addView(
                TextView(this).apply {
                    text = summary
                    textSize = 12f
                    setTextColor(Color.parseColor("#444444"))
                    setPadding(0, 12, 0, 2)
                }
            )

            for (post in scan.posts) {
                val who = if (post.channelUsername.isBlank()) "" else "@${post.channelUsername}: "
                val detail = when {
                    post.success -> "✅ $who${post.snippet}"
                    // Dòng ghi chú/lỗi mức kênh: không có nội dung bài nào để
                    // hiện, chỉ hiện đúng lý do cho gọn và dễ đọc.
                    !post.isPostAttempt -> "⚠️ $who${post.message}"
                    else -> {
                        val base = post.snippet.ifBlank { "(không có nội dung)" }
                        "❌ $who$base — ${post.message}"
                    }
                }

                telegramScanLogContainer.addView(
                    TextView(this).apply {
                        text = detail
                        textSize = 12f
                        setPadding(16, 2, 0, 2)
                        // Xanh = đăng thành công, cam = ghi chú/lỗi tạm thời
                        // (mạng chập chờn), đỏ = đăng bài thất bại thật sự.
                        setTextColor(
                            Color.parseColor(
                                when {
                                    post.success -> "#2E7D32"
                                    !post.isPostAttempt -> "#E08600"
                                    else -> "#C62828"
                                }
                            )
                        )
                    }
                )
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
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }
}
