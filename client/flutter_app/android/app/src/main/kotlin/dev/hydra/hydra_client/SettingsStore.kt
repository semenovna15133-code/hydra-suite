package dev.hydra.hydra_client

import android.content.Context

/** Пользовательские настройки Hydra (SharedPreferences). */
object SettingsStore {
    private const val PREFS = "hydra_settings"
    private const val KEY_VK_HASHES = "vk_hashes"
    private const val KEY_WORKERS_MODE = "workers_mode"
    private const val KEY_WORKERS = "workers"
    private const val KEY_ADVANCED_UI = "advanced_ui"
    private const val KEY_ONBOARDED = "onboarded"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getVkHashes(ctx: Context): String = prefs(ctx).getString(KEY_VK_HASHES, "") ?: ""
    fun setVkHashes(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_VK_HASHES, v).apply()
    fun getHashList(ctx: Context): List<String> =
        getVkHashes(ctx).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun isAutoWorkers(ctx: Context): Boolean = prefs(ctx).getString(KEY_WORKERS_MODE, "auto") == "auto"
    fun setWorkersMode(ctx: Context, auto: Boolean) =
        prefs(ctx).edit().putString(KEY_WORKERS_MODE, if (auto) "auto" else "manual").apply()
    fun getManualWorkers(ctx: Context): Int = prefs(ctx).getInt(KEY_WORKERS, 24)
    fun setManualWorkers(ctx: Context, w: Int) = prefs(ctx).edit().putInt(KEY_WORKERS, w).apply()

    /** Авто: cores*6, округлённое до 9; clamp [9, min(27*hashes, 108)]. */
    fun resolveWorkers(ctx: Context): Int {
        val hashes = getHashList(ctx).size.coerceAtLeast(1)
        val maxByHash = (27 * hashes).coerceAtMost(108)
        if (!isAutoWorkers(ctx)) return getManualWorkers(ctx).coerceIn(9, maxByHash)
        val cores = Runtime.getRuntime().availableProcessors()
        val rounded = ((cores * 6 + 4) / 9) * 9
        return rounded.coerceIn(9, maxByHash)
    }

    fun isAdvancedUi(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ADVANCED_UI, false)
    fun setAdvancedUi(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_ADVANCED_UI, v).apply()
    fun isOnboarded(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONBOARDED, false)
    fun setOnboarded(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_ONBOARDED, v).apply()
}
