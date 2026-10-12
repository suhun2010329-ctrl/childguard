package com.jel.childguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 5초마다 전면 앱을 확인하고, 한도를 넘은 앱이면 전체화면으로 덮는다. */
class MonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "childguard_monitor"
        private const val NOTI_ID = 1001
        private const val INTERVAL_MS = 5_000L

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, MonitorService::class.java))
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private var overlaySub: TextView? = null
    private var lastForeground: String? = null

    private val tick = object : Runnable {
        override fun run() { check(); handler.postDelayed(this, INTERVAL_MS) }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTI_ID, buildNotification())
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hideOverlay()
        super.onDestroy()
    }

    private fun check() {
        if (!UsageHelper.hasUsagePermission(this)) return
        UsageHelper.currentForegroundApp(this)?.let { lastForeground = it }
        val pkg = lastForeground ?: return

        if (pkg == packageName) { hideOverlay(); return }

        val limit = Prefs.getLimit(this, pkg)
        if (limit <= 0) { hideOverlay(); return }

        val used = UsageHelper.todayUsageMillis(this)[pkg] ?: 0L
        if (used >= limit * 60_000L) showOverlay(label(pkg), limit) else hideOverlay()
    }

    private fun label(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun showOverlay(appName: String, limit: Int) {
        val msg = "$appName 오늘 ${limit}분을 모두 사용했어요"
        if (overlay != null) { overlaySub?.text = msg; return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F21B1B2F"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true
        }
        root.addView(TextView(this).apply {
            text = "사용 시간이 끝났어요"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
        })
        overlaySub = TextView(this).apply {
            text = msg
            setTextColor(Color.parseColor("#C9C9D6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        }
        root.addView(overlaySub)
        root.addView(Button(this).apply {
            text = "홈으로 돌아가기"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                hideOverlay()
            }
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try { wm.addView(root, params); overlay = root } catch (e: Exception) { overlay = null }
    }

    private fun hideOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        overlaySub = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "사용시간 관리", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("사용시간 관리 실행 중")
            .setContentText("앱별 하루 한도를 확인하고 있어요")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
