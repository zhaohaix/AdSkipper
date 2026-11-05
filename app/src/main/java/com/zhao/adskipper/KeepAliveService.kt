package com.zhao.adskipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 保活服务 + 亮屏自愈。
 *
 * 作用一：以前台服务方式运行，把进程优先级顶上去，降低被系统冻结/回收的概率。
 * 作用二：监听亮屏事件，每次解锁时检查无障碍是否被关掉，被关就写回去
 *        （写回需要 WRITE_SECURE_SETTINGS，没有该权限时只做检查不做动作）。
 */
class KeepAliveService : Service() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastKnownState = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> selfHeal("亮屏")
            }
        }
    }

    /** 每 60 秒体检一次：只记状态变化，掉线就尝试写回 */
    private val watchdog = object : Runnable {
        override fun run() {
            val enabled = A11ySelfHeal.isEnabled(this@KeepAliveService)
            if (enabled != lastKnownState) {
                val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                Log.w(TAG, "无障碍状态变化 @$t -> ${if (enabled) "已恢复" else "掉线"}")
                lastKnownState = enabled
            }
            if (!enabled) selfHeal("定时检查")
            handler.postDelayed(this, checkInterval())
        }
    }

    /** 稳定模式体检更勤快（30 秒），普通模式 60 秒 */
    private fun checkInterval(): Long = if (Prefs.isStable(this)) 30_000L else 60_000L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startAsForeground()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(screenReceiver, filter) }

        lastKnownState = A11ySelfHeal.isEnabled(this)
        handler.postDelayed(watchdog, checkInterval())

        selfHeal("服务启动")
        Log.i(TAG, "保活服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统杀掉后尽量自动重启
        return START_STICKY
    }

    /** 稳定模式下，用户把任务划掉也尝试把保活拉回来 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.isStable(this)) {
            runCatching {
                val i = Intent(this, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
                else startService(i)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun selfHeal(reason: String) {
        if (!A11ySelfHeal.canSelfHeal(this)) return
        if (A11ySelfHeal.isEnabled(this)) return

        val ok = A11ySelfHeal.ensureEnabled(this)
        Log.i(TAG, "$reason 自愈：无障碍被关，尝试写回 -> $ok")
    }

    private fun startAsForeground() {
        val channelId = CHANNEL_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "后台保活", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持无障碍服务存活，用于自动跳过开屏广告"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_skip)
            .setContentTitle("跳广告正在运行")
            .setContentText("保持无障碍服务不被系统回收")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        Log.i(TAG, "保活服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AdSkip"
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1001

        /** 服务是否已经在跑，避免每次回到界面都重复 startForegroundService */
        @Volatile
        var isRunning = false
            private set
    }
}
