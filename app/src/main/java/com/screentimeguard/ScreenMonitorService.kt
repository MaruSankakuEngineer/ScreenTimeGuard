package com.screentimeguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class ScreenMonitorService : Service() {
    private var screenOnStartTime: Long = 0
    private var isScreenOn = false
    private lateinit var powerManager: PowerManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var monitoringStartTime: LocalTime
    private lateinit var monitoringEndTime: LocalTime
    private var warningThresholdMinutes: Int = 30
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private var lastWarningTime: Long = 0
    private var handler: Handler? = null
    private var runnable: Runnable? = null

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "screen_monitor_channel"
        private const val NOTIFICATION_ID = 1
        private const val WARNING_NOTIFICATION_ID = 2
        private const val TAG = "ScreenMonitorService"
        private const val WARNING_INTERVAL_MINUTES = 5 // 警告通知の間隔（分）
        private const val RESTART_ALARM_REQUEST_CODE = 1001
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        handler = Handler(Looper.getMainLooper())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 常にSharedPreferencesから最新の設定を読み込む
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val startTimeStr = prefs.getString(MainActivity.PREF_START_TIME, "22:00") ?: "22:00"
        val endTimeStr = prefs.getString(MainActivity.PREF_END_TIME, "06:00") ?: "06:00"
        warningThresholdMinutes = prefs.getInt(MainActivity.PREF_THRESHOLD, 30)

        try {
            monitoringStartTime = LocalTime.parse(startTimeStr, timeFormatter)
            monitoringEndTime = LocalTime.parse(endTimeStr, timeFormatter)

            Log.d(TAG, "Service started with settings from SharedPreferences: " +
                "Start=$startTimeStr (${monitoringStartTime}), " +
                "End=$endTimeStr (${monitoringEndTime}), " +
                "Threshold=$warningThresholdMinutes")

            startForeground(NOTIFICATION_ID, createNotification("スクリーン監視中\n監視時間: $startTimeStr - $endTimeStr"))
            
            // スクリーンの状態を定期的にチェック
            runnable = object : Runnable {
                override fun run() {
                    checkScreenState()
                    handler?.postDelayed(this, 1000)
                }
            }
            handler?.postDelayed(runnable!!, 1000)

            // サービスの再起動アラームを設定
            scheduleServiceRestart()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            stopSelf()
        }

        return START_STICKY
    }

    private fun scheduleServiceRestart() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val startTimeStr = prefs.getString(MainActivity.PREF_START_TIME, "22:00") ?: "22:00"
        val endTimeStr = prefs.getString(MainActivity.PREF_END_TIME, "06:00") ?: "06:00"
        val threshold = prefs.getInt(MainActivity.PREF_THRESHOLD, 30)

        Log.d(TAG, "Scheduling service restart with settings: Start=$startTimeStr, End=$endTimeStr, Threshold=$threshold")

        val restartIntent = Intent(this, ScreenMonitorService::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            RESTART_ALARM_REQUEST_CODE,
            restartIntent,
            flags
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // 1分後に再起動するアラームを設定（アプリがキルされた場合のバックアップ）
        alarmManager.setExact(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 60 * 1000, // 1分後
            pendingIntent
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Service task removed, scheduling restart")
        scheduleServiceRestart()
        super.onTaskRemoved(rootIntent)
    }

    private fun checkScreenState() {
        val currentTime = LocalTime.now()
        val isWithinMonitoringHours = if (monitoringStartTime.isBefore(monitoringEndTime)) {
            currentTime.isAfter(monitoringStartTime) && currentTime.isBefore(monitoringEndTime)
        } else {
            // 開始時刻が終了時刻より後の場合（例：22:00-06:00）
            currentTime.isAfter(monitoringStartTime) || currentTime.isBefore(monitoringEndTime)
        }

        val isScreenOn = powerManager.isInteractive

        if (isWithinMonitoringHours) {
            if (isScreenOn && !this.isScreenOn) {
                // 画面がONになった時
                screenOnStartTime = System.currentTimeMillis()
                this.isScreenOn = true
                Log.d(TAG, "Screen turned ON at ${LocalTime.now()}")
            } else if (!isScreenOn && this.isScreenOn) {
                // 画面がOFFになった時
                this.isScreenOn = false
                Log.d(TAG, "Screen turned OFF at ${LocalTime.now()}")
            } else if (isScreenOn) {
                // 画面ON中の処理
                val screenOnDuration = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - screenOnStartTime)
                
                if (screenOnDuration >= warningThresholdMinutes) {
                    val timeSinceLastWarning = System.currentTimeMillis() - lastWarningTime
                    
                    // 前回の警告から5分以上経過している場合に警告を表示
                    if (TimeUnit.MILLISECONDS.toMinutes(timeSinceLastWarning) >= WARNING_INTERVAL_MINUTES) {
                        showWarningNotification(screenOnDuration)
                        lastWarningTime = System.currentTimeMillis()
                    }
                }
            }
        } else {
            // 監視時間外の場合、画面状態のみ更新
            this.isScreenOn = isScreenOn
            if (isScreenOn) {
                screenOnStartTime = System.currentTimeMillis()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Screen Monitor Service"
            val descriptionText = "画面使用時間の監視サービス"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
                lightColor = Color.RED
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Time Guard")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        return notificationBuilder.build()
    }

    private fun showWarningNotification(screenOnDuration: Long) {
        val channelId = NOTIFICATION_CHANNEL_ID
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("長時間の使用警告")
            .setContentText("画面の使用時間が${screenOnDuration}分を超えています")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 500, 250, 500)) // バイブレーションパターン
            .setLights(Color.RED, 3000, 3000) // LED点滅
            .setAutoCancel(true)
            .build()

        notificationManager.notify(WARNING_NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        handler?.removeCallbacks(runnable!!)
        handler = null
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.cancel(WARNING_NOTIFICATION_ID)

        // サービスが予期せず終了した場合、再起動を試みる
        scheduleServiceRestart()
    }
} 