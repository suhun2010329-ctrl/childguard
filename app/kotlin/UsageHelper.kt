package com.jel.childguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

/**
 * 오늘 사용량과 현재 전면 앱 계산.
 * queryUsageStats 는 제조사별 집계 오차가 커서 raw 이벤트를 직접 누적한다.
 */
object UsageHelper {

    fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun hasUsagePermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** 자정부터 지금까지 앱별 전면 사용 시간(ms) */
    fun todayUsageMillis(ctx: Context): Map<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, Long>()
        val openedAt = mutableMapOf<String, Long>()

        val events = usm.queryEvents(todayStart(), now)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            when (ev.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> openedAt[pkg] = ev.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val from = openedAt.remove(pkg)
                    if (from != null && ev.timeStamp > from)
                        result[pkg] = (result[pkg] ?: 0L) + (ev.timeStamp - from)
                }
            }
        }
        openedAt.forEach { (pkg, from) ->
            if (now > from) result[pkg] = (result[pkg] ?: 0L) + (now - from)
        }
        return result
    }

    /** 최근 lookbackMs 안에 마지막으로 전면에 올라온 앱 */
    fun currentForegroundApp(ctx: Context, lookbackMs: Long = 15_000L): String? {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - lookbackMs, now)
        val ev = UsageEvents.Event()
        var pkg: String? = null
        var t = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && ev.timeStamp >= t) {
                t = ev.timeStamp; pkg = ev.packageName
            }
        }
        return pkg
    }
}
