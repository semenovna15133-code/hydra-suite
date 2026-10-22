package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object ProtocolMemory {
    private const val TAG = "ProtocolMemory"
    private const val PREFS_NAME = "protocol_memory"
    
    fun getPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun getFailCount(ctx: Context, protocol: String): Int =
        getPrefs(ctx).getInt("fail_$protocol", 0)
    
    fun getSuccessCount(ctx: Context, protocol: String): Int =
        getPrefs(ctx).getInt("success_$protocol", 0)
    
    fun getPenaltyUntil(ctx: Context, protocol: String): Long =
        getPrefs(ctx).getLong("penalty_$protocol", 0L)
    
    fun isPenalized(ctx: Context, protocol: String): Boolean {
        val until = getPenaltyUntil(ctx, protocol)
        return until > android.os.SystemClock.elapsedRealtime()
    }
    
    fun recordSuccess(ctx: Context, protocol: String) {
        getPrefs(ctx).edit()
            .putInt("fail_$protocol", 0)
            .putInt("success_$protocol", getSuccessCount(ctx, protocol) + 1)
            .putLong("penalty_$protocol", 0L)
            .apply()
        Log.i(TAG, "Success recorded: $protocol")
    }
    
    fun recordFailure(ctx: Context, protocol: String) {
        val failCount = getFailCount(ctx, protocol) + 1
        val penaltyMs = when {
            failCount <= 2 -> 2 * 60 * 1000L
            failCount <= 5 -> 10 * 60 * 1000L
            else -> 30 * 60 * 1000L
        }
        val penaltyUntil = android.os.SystemClock.elapsedRealtime() + penaltyMs
        getPrefs(ctx).edit()
            .putInt("fail_$protocol", failCount)
            .putInt("success_$protocol", 0)
            .putLong("penalty_$protocol", penaltyUntil)
            .apply()
        Log.w(TAG, "Failure recorded: $protocol (fail=$failCount, penalty=${penaltyMs/1000}s)")
    }
    
    fun dumpState(ctx: Context): String {
        val protocols = listOf("AmneziaWG", "WDTT", "AIVPN")
        val now = android.os.SystemClock.elapsedRealtime()
        return protocols.joinToString(", ") { p ->
            val fail = getFailCount(ctx, p)
            val success = getSuccessCount(ctx, p)
            val penalty = getPenaltyUntil(ctx, p)
            val remaining = if (penalty > now) (penalty - now) / 1000 else 0
            "$p(fail=$fail,success=$success,penalty=${remaining}s)"
        }
    }

    fun clearPenalties(ctx: Context) {
        val ed = getPrefs(ctx).edit()
        for (p in listOf("AmneziaWG", "WDTT", "AIVPN")) {
            ed.putInt("fail_$p", 0).putLong("penalty_$p", 0L)
        }
        ed.apply()
        Log.i(TAG, "Penalties cleared (network change)")
    }

    fun resetAll(ctx: Context) {
        getPrefs(ctx).edit().clear().apply()
        Log.i(TAG, "All protocol memory reset")
    }
}
