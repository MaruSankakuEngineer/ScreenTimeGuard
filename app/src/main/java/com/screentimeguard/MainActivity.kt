package com.screentimeguard

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import android.app.PendingIntent
import android.content.Context
import java.util.*
import android.text.Editable
import android.text.TextWatcher
import android.util.Log

class MainActivity : AppCompatActivity() {
    private var isMonitoring = false
    private lateinit var startTimeButton: Button
    private lateinit var endTimeButton: Button
    private lateinit var thresholdInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var alarmManager: AlarmManager

    companion object {
        const val PREF_START_TIME = "start_time"
        const val PREF_END_TIME = "end_time"
        const val PREF_THRESHOLD = "threshold"
        const val PREF_IS_MONITORING_ENABLED = "is_monitoring_enabled"
        const val DEFAULT_START_TIME = "22:00"
        const val DEFAULT_END_TIME = "06:00"
        const val DEFAULT_THRESHOLD = 30
        private const val ALARM_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UIコンポーネントの初期化
        startTimeButton = findViewById(R.id.startTimeButton)
        endTimeButton = findViewById(R.id.endTimeButton)
        thresholdInput = findViewById(R.id.thresholdInput)
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)

        // SharedPreferencesの初期化
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 監視フラグの読み込みと状態の復元
        isMonitoring = prefs.getBoolean(PREF_IS_MONITORING_ENABLED, false)
        updateMonitoringStatus()

        // 保存された設定の読み込みとUIの更新
        loadSavedSettings()

        // 監視が有効な場合、アプリ起動時に監視を開始
        if (isMonitoring) {
            startMonitoring()
        }

        // ボタンのクリックリスナー設定
        toggleButton.setOnClickListener {
            isMonitoring = !isMonitoring
            prefs.edit().putBoolean(PREF_IS_MONITORING_ENABLED, isMonitoring).apply()
            
            if (isMonitoring) {
                startMonitoring()
            } else {
                stopMonitoring()
            }
            updateMonitoringStatus()
        }

        startTimeButton.setOnClickListener {
            showTimePickerDialog(true)
        }

        endTimeButton.setOnClickListener {
            showTimePickerDialog(false)
        }

        // 閾値が変更された時の処理
        thresholdInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val threshold = s?.toString()?.toIntOrNull() ?: DEFAULT_THRESHOLD
                prefs.edit().putInt(PREF_THRESHOLD, threshold).apply()
                updateServiceIfRunning()
            }
        })
    }

    private fun loadSavedSettings() {
        val startTime = prefs.getString(PREF_START_TIME, DEFAULT_START_TIME) ?: DEFAULT_START_TIME
        val endTime = prefs.getString(PREF_END_TIME, DEFAULT_END_TIME) ?: DEFAULT_END_TIME
        val threshold = prefs.getInt(PREF_THRESHOLD, DEFAULT_THRESHOLD)

        Log.d("MainActivity", "Loading saved settings: Start=$startTime, End=$endTime, Threshold=$threshold")

        updateTimeButtonText(startTime, true)
        updateTimeButtonText(endTime, false)
        thresholdInput.setText(threshold.toString())
    }

    private fun showTimePickerDialog(isStartTime: Boolean) {
        val timeStr = if (isStartTime) {
            prefs.getString(PREF_START_TIME, DEFAULT_START_TIME) ?: DEFAULT_START_TIME
        } else {
            prefs.getString(PREF_END_TIME, DEFAULT_END_TIME) ?: DEFAULT_END_TIME
        }
        
        val (hour, minute) = timeStr.split(":").map { it.toInt() }
        
        TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                val newTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                
                // 設定を保存
                prefs.edit().apply {
                    if (isStartTime) {
                        putString(PREF_START_TIME, newTime)
                    } else {
                        putString(PREF_END_TIME, newTime)
                    }
                    apply()
                }

                // UIを更新
                updateTimeButtonText(newTime, isStartTime)
                
                // ログ出力
                Log.d("MainActivity", "Time updated: ${if (isStartTime) "Start" else "End"}=$newTime")
                Log.d("MainActivity", "Current settings in SharedPreferences: " +
                    "Start=${prefs.getString(PREF_START_TIME, DEFAULT_START_TIME)}, " +
                    "End=${prefs.getString(PREF_END_TIME, DEFAULT_END_TIME)}")

                // サービスを更新
                updateServiceIfRunning()
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun updateTimeButtonText(time: String, isStartTime: Boolean) {
        val (hour, minute) = time.split(":").map { it.toInt() }
        val timeText = when {
            hour < 12 -> "午前${hour}時${minute}分"
            hour == 12 -> "午後12時${minute}分"
            else -> "午後${hour - 12}時${minute}分"
        }
        
        if (isStartTime) {
            startTimeButton.text = timeText
        } else {
            endTimeButton.text = timeText
        }
    }

    private fun startMonitoring() {
        val startTime = prefs.getString(PREF_START_TIME, DEFAULT_START_TIME) ?: DEFAULT_START_TIME
        val endTime = prefs.getString(PREF_END_TIME, DEFAULT_END_TIME) ?: DEFAULT_END_TIME
        val threshold = thresholdInput.text.toString().toIntOrNull() ?: DEFAULT_THRESHOLD

        Log.d("MainActivity", "Starting monitoring with settings: Start=$startTime, End=$endTime, Threshold=$threshold")

        // サービスの開始
        val serviceIntent = Intent(this, ScreenMonitorService::class.java).apply {
            putExtra(PREF_START_TIME, startTime)
            putExtra(PREF_END_TIME, endTime)
            putExtra(PREF_THRESHOLD, threshold)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 次の監視開始時刻のアラームを設定
        scheduleNextMonitoring()
    }

    private fun scheduleNextMonitoring() {
        val startTime = prefs.getString(PREF_START_TIME, DEFAULT_START_TIME) ?: DEFAULT_START_TIME
        val (startHour, startMinute) = startTime.split(":").map { it.toInt() }

        // 次の監視開始時刻を計算
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMinute)
            set(Calendar.SECOND, 0)
            
            // 現在時刻が開始時刻を過ぎている場合は翌日の開始時刻を設定
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // アラームの設定
        val intent = Intent(this, ScreenMonitorService::class.java).apply {
            putExtra(PREF_START_TIME, startTime)
            putExtra(PREF_END_TIME, prefs.getString(PREF_END_TIME, DEFAULT_END_TIME))
            putExtra(PREF_THRESHOLD, thresholdInput.text.toString().toIntOrNull() ?: DEFAULT_THRESHOLD)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getService(
            this,
            ALARM_REQUEST_CODE,
            intent,
            flags
        )

        // 既存のアラームをキャンセル
        alarmManager.cancel(pendingIntent)

        // 新しいアラームを設定
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12以降
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent),
                    pendingIntent
                )
            } else {
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
                )
            }
        } else {
            // Android 11以前
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent),
                pendingIntent
            )
        }

        Log.d("MainActivity", "Scheduled next monitoring at ${calendar.time}, with settings: " +
            "Start=${prefs.getString(PREF_START_TIME, DEFAULT_START_TIME)}, " +
            "End=${prefs.getString(PREF_END_TIME, DEFAULT_END_TIME)}")
    }

    private fun stopMonitoring() {
        // サービスの停止
        stopService(Intent(this, ScreenMonitorService::class.java))
        
        // アラームのキャンセル
        val pendingIntent = PendingIntent.getService(
            this,
            ALARM_REQUEST_CODE,
            Intent(this, ScreenMonitorService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun updateServiceIfRunning() {
        if (isMonitoring) {
            startMonitoring()
        }
    }

    private fun updateMonitoringStatus() {
        statusText.text = if (isMonitoring) "監視中" else "停止中"
        toggleButton.text = if (isMonitoring) "停止" else "開始"
    }
} 