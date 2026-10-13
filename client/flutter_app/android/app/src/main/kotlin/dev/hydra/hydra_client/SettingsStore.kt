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
    private const val KEY_PREFERRED_PROTO = "preferred_protocol"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun normalizeHashes(raw: String): String {
        val parts = raw.split(Regex("[,\\s]+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val cleaned = parts.map(::extractHash).filter { vkHashRe.matches(it) }
        return cleaned.distinct().joinToString(",")
    }

    fun getVkHashes(ctx: Context): String {
        val raw = prefs(ctx).getString(KEY_VK_HASHES, "") ?: ""
        if (raw.isEmpty()) return ""
        return normalizeHashes(raw)
    }
    fun setVkHashes(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_VK_HASHES, v).apply()
    fun getHashList(ctx: Context): List<String> {
        val raw = getVkHashes(ctx)
        if (raw.isEmpty()) return emptyList()
        return normalizeHashes(raw).split(",").filter { it.isNotEmpty() }
    }
    
    fun getVkHashesRaw(ctx: Context): String = getVkHashes(ctx)

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

    // Продвинутый режим: туннель и обновления
    fun getMtu(ctx: Context): Int = prefs(ctx).getInt("mtu", 1420)
    fun setMtu(ctx: Context, v: Int) = prefs(ctx).edit().putInt("mtu", v).apply()
    fun getDnsOverride(ctx: Context): String = prefs(ctx).getString("dns_override", "") ?: ""
    fun setDnsOverride(ctx: Context, v: String) = prefs(ctx).edit().putString("dns_override", v).apply()
    fun isKillSwitch(ctx: Context): Boolean = prefs(ctx).getBoolean("kill_switch", false)
    fun setKillSwitch(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("kill_switch", v).apply()
    fun getUpdateUrl(ctx: Context): String = prefs(ctx).getString("update_url", "") ?: ""
    fun setUpdateUrl(ctx: Context, v: String) = prefs(ctx).edit().putString("update_url", v).apply()
    fun getSkippedUpdate(ctx: Context): String = prefs(ctx).getString("skipped_update", "") ?: ""
    fun setSkippedUpdate(ctx: Context, v: String) = prefs(ctx).edit().putString("skipped_update", v).apply()
    fun getLastCheckedUpdate(ctx: Context): String = prefs(ctx).getString("last_checked_update", "") ?: ""
    fun setLastCheckedUpdate(ctx: Context, v: String) = prefs(ctx).edit().putString("last_checked_update", v).apply()
    fun getPendingImport(ctx: Context): String = prefs(ctx).getString("pending_import", "") ?: ""
    fun setPendingImport(ctx: Context, v: String) = prefs(ctx).edit().putString("pending_import", v).apply()

    /** Предпочитаемый протокол: "auto"|"awg"|"aivpn"|"wdtt". */
    fun getPreferredProtocol(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_PREFERRED_PROTO, "auto") ?: "auto"
        return if (v in listOf("auto", "awg", "aivpn", "wdtt")) v else "auto"
    }
    fun setPreferredProtocol(ctx: Context, v: String) =
        prefs(ctx).edit().putString(KEY_PREFERRED_PROTO, v).apply()

    /** Нормализация списка VK-хешей: из ссылки vk.ru/call/join/XXX оставляем XXX. */
    private val vkHashRe = Regex("^[A-Za-z0-9_\\-]{16,512}$")
    fun extractHash(input: String): String {
        var s = input.trim()
        // Убираем обрамляющие кавычки/скобки
        while (s.length >= 2 && (s[0] in "<>\"'" && s.last() in "<>\"'")) {
            s = s.substring(1, s.length - 1)
        }
        val lower = s.lowercase()
        val i = lower.indexOf("/call/join/")
        s = when {
            i >= 0 -> s.substring(i + "/call/join/".length)
            lower.startsWith("http://") || lower.startsWith("https://") -> return ""
            else -> s
        }
        val stop = s.indexOfFirst { it == '?' || it == '#' || it == '/' || it.isWhitespace() }
        if (stop >= 0) s = s.substring(0, stop)
        return s.trimEnd('/').trim()
    }


}
