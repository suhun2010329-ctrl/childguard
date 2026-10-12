package com.jel.childguard

import android.content.Context

/** 앱별 하루 한도(분), 부모 PIN, 서비스 on/off 저장 */
object Prefs {
    private const val FILE = "childguard_prefs"
    private const val KEY_PIN = "parent_pin"
    private const val KEY_ENABLED = "service_enabled"
    private const val LIMIT_PREFIX = "limit_"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getPin(ctx: Context): String? = sp(ctx).getString(KEY_PIN, null)
    fun setPin(ctx: Context, pin: String) = sp(ctx).edit().putString(KEY_PIN, pin).apply()
    fun hasPin(ctx: Context) = getPin(ctx) != null

    fun isEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun getAllLimits(ctx: Context): Map<String, Int> =
        sp(ctx).all.mapNotNull { (k, v) ->
            if (k.startsWith(LIMIT_PREFIX) && v is Int) k.removePrefix(LIMIT_PREFIX) to v else null
        }.toMap()

    fun getLimit(ctx: Context, pkg: String) = sp(ctx).getInt(LIMIT_PREFIX + pkg, 0)

    /** 0 이하면 제한 해제 */
    fun setLimit(ctx: Context, pkg: String, minutes: Int) {
        val e = sp(ctx).edit()
        if (minutes <= 0) e.remove(LIMIT_PREFIX + pkg) else e.putInt(LIMIT_PREFIX + pkg, minutes)
        e.apply()
    }
}
