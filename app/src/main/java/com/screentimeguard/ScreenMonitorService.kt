package com.screentimeguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class ScreenMonitorService : Service() {
    private var screenOnStartTime = 0L
    private var isScreenOn = false
    private lateinit var powerManager: PowerManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var monitoringStartTime: LocalTime
    private lateinit var monitoringEndTime: LocalTime
    private var warningThresholdMinutes: Int = 30
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private var lastWarningTime = 0L
    private var handler: Handler? = null
    private var runnable: Runnable? = null
    private var lastMonitoringDate: LocalDate? = null  // 最後の監視開始日を記録
    private var totalScreenTimeToday = 0L  // 1日の累計スクリーンタイム
    private var lastScreenOffTime = 0L     // 最後に画面をOFFにした時刻

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "screen_monitor_channel"
        private const val NOTIFICATION_ID = 1
        private const val WARNING_NOTIFICATION_ID = 2
        private const val TAG = "ScreenMonitorService"
        private const val WARNING_INTERVAL_MINUTES = 5 // 警告通知の間隔（分）
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        handler = Handler(Looper.getMainLooper())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // SharedPreferencesから設定を読み込む
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val startTimeStr = prefs.getString(MainActivity.PREF_START_TIME, "22:00") ?: "22:00"
            val endTimeStr = prefs.getString(MainActivity.PREF_END_TIME, "06:00") ?: "06:00"
            warningThresholdMinutes = prefs.getInt(MainActivity.PREF_THRESHOLD, 30)

            monitoringStartTime = LocalTime.parse(startTimeStr, timeFormatter)
            monitoringEndTime = LocalTime.parse(endTimeStr, timeFormatter)

            // 監視が有効な場合のみサービスを開始
            if (prefs.getBoolean(MainActivity.PREF_IS_MONITORING_ENABLED, false)) {
                // フォアグラウンドサービスとして開始（通知なし）
                startForeground(NOTIFICATION_ID, createSilentNotification())
                
                // スクリーンの状態を定期的にチェック
                runnable?.let { handler?.removeCallbacks(it) }
                runnable = object : Runnable {
                    override fun run() {
                        checkScreenState()
                        handler?.postDelayed(this, 1000)
                    }
                }
                handler?.postDelayed(runnable!!, 1000)
                
                Log.d(TAG, "Service started successfully")
            } else {
                Log.d(TAG, "Monitoring is disabled, stopping service")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        runnable?.let { handler?.removeCallbacks(it) }
        handler = null
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.cancel(WARNING_NOTIFICATION_ID)
        lastMonitoringDate = null
    }

    private fun checkScreenState() {
        val currentTime = LocalTime.now()
        val currentDate = LocalDate.now()
        val isWithinMonitoringHours = if (monitoringStartTime.isBefore(monitoringEndTime)) {
            currentTime.isAfter(monitoringStartTime) && currentTime.isBefore(monitoringEndTime)
        } else {
            currentTime.isAfter(monitoringStartTime) || currentTime.isBefore(monitoringEndTime)
        }

        // 監視時間外になった時の処理
        if (!isWithinMonitoringHours) {
            if (this.isScreenOn) {
                // 画面がONの場合、最後の使用時間を加算
                val finalSessionDuration = TimeUnit.MILLISECONDS.toMinutes(
                    System.currentTimeMillis() - screenOnStartTime
                )
                totalScreenTimeToday += finalSessionDuration
                Log.d(TAG, "Final screen time before monitoring end: $totalScreenTimeToday minutes")
            }
            // 監視時間外の場合、状態をリセット
            this.isScreenOn = powerManager.isInteractive
            screenOnStartTime = 0L
            lastWarningTime = 0L
            totalScreenTimeToday = 0L  // 監視時間外になったら累計時間をリセット
            lastMonitoringDate = null
            return  // 監視時間外の場合は以降の処理をスキップ
        }

        // 監視開始時刻になったときの処理（監視時間内の場合のみ）
        if (lastMonitoringDate == null || !currentDate.isEqual(lastMonitoringDate)) {
            // 新しい日の監視開始時に通知を表示し、累計時間をリセット
            notificationManager.notify(
                NOTIFICATION_ID,
                createNotification("スクリーン監視中\n監視時間: ${timeFormatter.format(monitoringStartTime)} - ${timeFormatter.format(monitoringEndTime)}")
            )
            lastMonitoringDate = currentDate
            totalScreenTimeToday = 0L  // 新しい日の監視開始時に累計時間をリセット
            Log.d(TAG, "Started monitoring for new day: $currentDate")
        }

        val isScreenOn = powerManager.isInteractive

        if (isScreenOn && !this.isScreenOn) {
            // 画面がONになった時
            screenOnStartTime = System.currentTimeMillis()
            this.isScreenOn = true
            Log.d(TAG, "Screen turned ON at ${LocalTime.now()}")
        } else if (!isScreenOn && this.isScreenOn) {
            // 画面がOFFになった時
            this.isScreenOn = false
            if (screenOnStartTime > 0) {  // 監視開始後の画面OFFのみカウント
                val currentSessionDuration = TimeUnit.MILLISECONDS.toMinutes(
                    System.currentTimeMillis() - screenOnStartTime
                )
                totalScreenTimeToday += currentSessionDuration
                Log.d(TAG, "Screen turned OFF at ${LocalTime.now()}, Total screen time today: $totalScreenTimeToday minutes")
            }
            screenOnStartTime = 0L  // 次回の画面ON時の計測のためにリセット
        } else if (isScreenOn) {
            // 画面ON中の処理
            if (screenOnStartTime > 0) {  // 監視開始後の画面ONのみカウント
                val currentSessionDuration = TimeUnit.MILLISECONDS.toMinutes(
                    System.currentTimeMillis() - screenOnStartTime
                )
                val totalDuration = totalScreenTimeToday + currentSessionDuration
                
                if (totalDuration >= warningThresholdMinutes) {
                    // 警告通知の表示判定
                    val shouldShowWarning = if (lastWarningTime == 0L) {
                        true  // 初回は即座に警告
                    } else {
                        val timeSinceLastWarning = TimeUnit.MILLISECONDS.toMinutes(
                            System.currentTimeMillis() - lastWarningTime
                        )
                        timeSinceLastWarning >= WARNING_INTERVAL_MINUTES
                    }
                    
                    if (shouldShowWarning) {
                        showWarningNotification(totalDuration)
                        lastWarningTime = System.currentTimeMillis()
                        Log.d(TAG, "Warning notification shown at ${LocalTime.now()} for total duration: $totalDuration minutes")
                    }
                }
            }
        }
    }

    private fun createSilentNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen Time Guard")
            .setContentText("監視実行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen Time Guard")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(false)
            .build()
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

    private fun showWarningNotification(duration: Long) {
        val message = "監視開始：${timeFormatter.format(monitoringStartTime)}\n" +
                "監視からの累計使用時間：${duration}分"
        
        notificationManager.notify(
            WARNING_NOTIFICATION_ID,
            createWarningNotification(message)
        )
    }

    private fun createWarningNotification(message: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("長時間の使用警告")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setLights(Color.RED, 3000, 3000)
            .setAutoCancel(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
} 