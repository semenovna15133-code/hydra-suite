package dev.hydra.hydra_client.tunnel

import dev.hydra.hydra_client.HydraVpnService

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import tunnel.NetworkProbeResult

object SmartConnectManager {
    private var lastNetworkType: String? = null

    private const val TAG = "SmartConnectManager"
    
    private val providers = mutableListOf<TunnelProvider>()
    private var activeProvider: TunnelProvider? = null
    private var context: Context? = null
    private var keyId: String? = null
    
    /** Публичный доступ к активному провайдеру (для UI) */
    val active: TunnelProvider? get() = activeProvider
    
    @Volatile
    var lastLatencyMs: Long = 0
        private set
    
    @Volatile
    var lastProbe: NetworkProbeResult? = null
        private set

    /** true если для подключения не хватает VK-хешей (UI покажет онбординг). */
    @Volatile
    var needVkHashes: Boolean = false
        private set
    
    /** Callback для UI-сообщений о переключении протокола */
    var statusMessageCallback: ((String) -> Unit)? = null
    @Volatile
    var lastStatusMessage: String? = null
    
    fun registerProvider(provider: TunnelProvider) {
        providers.add(provider)
        Log.i(TAG, "Registered provider: ${provider.name} (${provider.serverId})")
    }
    private val connectMutex = kotlinx.coroutines.sync.Mutex()
    private var supervisedRetryJob: kotlinx.coroutines.Job? = null

    /** Single-flight: один connect-поток за раз; при null — надзорные ретраи с любых путей. */
    suspend fun autoConnect(ctx: Context, kid: String, retryAfterUnlock: Boolean = true): TunnelProvider?  {
        val result = connectMutex.withLock { autoConnectInner(ctx, kid, retryAfterUnlock) }
        if (result == null) scheduleSupervisedRetries(ctx, kid)
        return result
    }

    private fun scheduleSupervisedRetries(ctx: Context, kid: String) {
        supervisedRetryJob?.cancel()
        supervisedRetryJob = ncScope.launch {
            var delayMs = 15_000L
            repeat(4) { i ->
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(120_000L)
                if (!HydraVpnService.isRunning) {
                    Log.i(TAG, "Supervised retry #$i (autoConnect returned null)")
                    val p = connectMutex.withLock { autoConnectInner(ctx, kid, true) }
                    if (p != null) { Log.i(TAG, "Supervised retry OK: ${p?.name}"); return@launch }
                }
            }
            Log.e(TAG, "Supervised retries exhausted — waiting for network event")
        }
    }

    private suspend fun autoConnectInner(ctx: Context, kid: String, retryAfterUnlock: Boolean): TunnelProvider?  {
        // Сброс штрафов при смене сети (WiFi ↔ cellular)
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val currentType = when {
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            else -> "unknown"
        }
        if (lastNetworkType != null && lastNetworkType != currentType) {
            Log.i(TAG, "Network changed $lastNetworkType -> $currentType: resetting all penalties")
            ProtocolMemory.resetAll(ctx)
        }
        lastNetworkType = currentType

        context = ctx
        keyId = kid
        Log.i(TAG, "Starting Smart Connect...")
        
        // Парсим keyfile и строим провайдеры динамически
        val keysDir = java.io.File(ctx.filesDir, "keys")
        val keyFile = java.io.File(keysDir, "${sanitizeKeyId(kid)}.key")
        if (!keyFile.exists()) {
            Log.e(TAG, "Key file not found: ${keyFile.absolutePath}")
            return null
        }
        
        val kf = keyfilekt.Keyfile.parse(keyFile.absolutePath)
        
        // Очищаем старые провайдеры
        providers.clear()
        
        // Регистрируем провайдер для каждого peer
        for (peer in kf.peers) {
            val provider = when (peer.protocol) {
                "awg" -> AWGTunnelProvider(ctx, peer.serverId, peer.label, peer)
                "aivpn" -> AIVPNTunnelProvider(ctx, peer.serverId, peer.label, peer)
                "wdtt" -> WDTTTunnelProvider(ctx, peer.serverId, peer.label, peer)
                else -> {
                    Log.w(TAG, "Unknown protocol: ${peer.protocol}")
                    continue
                }
            }
            registerProvider(provider)
        }
        
        // Пробегаем по всем endpoint'ам для скоринга
        val firstEndpoint = kf.peers.firstOrNull()?.endpoint ?: "31.77.202.131:51820"
        val probe = EnvironmentProbe.probe(ctx, firstEndpoint)
        lastProbe = probe
        lastLatencyMs = probe.latencyMs
        
        val whiteList = EnvironmentProbe.whiteListDetected
        val hashCount = dev.hydra.hydra_client.SettingsStore.getHashList(ctx).size
        val preferred = dev.hydra.hydra_client.SettingsStore.getPreferredProtocol(ctx)

        val scored = providers.map { p ->
            var sc = p.score(probe)
            // Penalty: если протокол наказан — score=0
            if (ProtocolMemory.isPenalized(ctx, p.name)) {
                Log.w(TAG, "${p.name} is penalized, score forced to 0")
                sc = 0
            }
            if (whiteList) {
                sc = when (p.name) {
                    "WDTT" -> sc + 50          // обход белых списков через VK TURN
                    else -> 0                  // AIVPN/AWG в белых списках не живут
                }
            }
            p to sc
        }
        .sortedWith(compareByDescending<Pair<TunnelProvider, Int>> { it.second }
            .thenBy { when (it.first.name) {
                "AmneziaWG" -> 0
                "AIVPN" -> 1
                "WDTT" -> 2
                else -> 3
            } })

        // Белые списки + выбран WDTT, но хешей нет — просим пользователя добавить
        needVkHashes = whiteList && hashCount == 0 &&
            scored.firstOrNull()?.first?.name == "WDTT"
        if (needVkHashes) {
            Log.e(TAG, "White-list network but no VK hashes configured — ask user")
            return null
        }
        
        DevLog.i("SmartConnectManager", "Memory state: ${ProtocolMemory.dumpState(ctx)}")
        DevLog.i("SmartConnectManager", "Probe results: UDP=${probe.udpOk}, TCP=${probe.tcpOk}, VK=${probe.vkApiOk}, DPI=${probe.dpiDetected}, latency=${probe.latencyMs}ms")
        val scoredDetails = scored.map { (p, sc) ->
            val penalty = if (ProtocolMemory.isPenalized(ctx, p.name)) "penalized" else "ok"
            "${p.name}=$sc ($penalty)"
        }.joinToString(", ")
        DevLog.i("SmartConnectManager", "Scored details: [$scoredDetails], preferred=$preferred, whiteList=$whiteList")
        Log.i(TAG, "Providers scored: ${scored.map { "${it.first.name}=${it.second}" }}, preferred=$preferred")
        
        // Evidence-timeout loop: пробуем каждого кандидата последовательно
        val candidates = if (preferred != "auto") {
            val pref = scored.find { it.first.name.equals(preferred, ignoreCase = true) && it.second > 0 }
            if (pref != null) {
                Log.i(TAG, "Preferred protocol: ${pref.first.name}, trying first")
                listOf(pref) + scored.filter { it.first.name != pref.first.name && it.second > 0 }
            } else {
                scored.filter { it.second > 0 }
            }
        } else {
            scored.filter { it.second > 0 }
        }

        // Tiebreaker: при равных баллах приоритет AWG > WDTT > AIVPN
        val tiebreakerOrder = mapOf("AmneziaWG" to 0, "AIVPN" to 1, "WDTT" to 2)
        val sorted = candidates.sortedWith(compareByDescending<Pair<dev.hydra.hydra_client.tunnel.TunnelProvider, Int>> { it.second }
            .thenBy { tiebreakerOrder[it.first.name] ?: 99 })
        val candidatesFinal = sorted
        
        if (candidates.isEmpty()) {
            if (retryAfterUnlock && ProtocolMemory.getPrefs(ctx).all.isNotEmpty()) {
                Log.w(TAG, "No viable candidates — emergency unlock penalties and rescore once")
                ProtocolMemory.clearPenalties(ctx)
                return autoConnectInner(ctx, kid, false)
            }
            Log.e(TAG, "No viable candidates (all scored 0 or penalized)")
            return null
        }
        
        for ((provider, score) in candidatesFinal) {
            Log.i(TAG, "Trying ${provider.name} (score=$score)...")
            uiMessage("Подключение через ${provider.name}...")
            
            val success = tryConnectWithEvidence(provider, kid)
            if (success) {
                Log.i(TAG, "✅ ${provider.name} connected successfully")
                ProtocolMemory.recordSuccess(ctx, provider.name)
                activeProvider = provider
                HealthMonitor.statusListener = { st -> ReconnectManager.onHealth(st) }
                HealthMonitor.start()
                return provider
            } else {
                Log.w(TAG, "❌ ${provider.name} failed, trying next...")
                ProtocolMemory.recordFailure(ctx, provider.name)
                provider.disconnect()
                uiMessage("${provider.name} недоступен, переключаюсь...")
                delay(1000) // даём teardown завершиться
            }
        }
        
        Log.e(TAG, "All candidates failed")
        return null
    }
    
    /**
     * Evidence-timeout: подключаем провайдер и ждём реального признака жизни.
     * Таймауты: AWG 15с, AIVPN 10с, WDTT 12с.
     */
    private suspend fun tryConnectWithEvidence(provider: TunnelProvider, kid: String): Boolean {
        val timeoutMs = when (provider.name) {
            "AmneziaWG" -> 12_000L
            "AIVPN" -> 10_000L
            "WDTT" -> 20_000L
            else -> 15_000L
        }
        
        val startTime = android.os.SystemClock.elapsedRealtime()
        provider.connect(kid)
        
        // Poll каждые 2 сек: проверяем handshake + traffic
        while (android.os.SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            delay(2000)
            
            // Признак жизни: handshake complete + ненулевой трафик или HEALTHY
            val activeName = dev.hydra.hydra_client.HydraVpnService.activeProviderName
            val alive = when (provider.name) {
                "WDTT" -> wdttSocksAlive() || hbHasBytes()
                else -> dev.hydra.hydra_client.HydraVpnService.isHandshakeComplete && activeName == provider.name
            }
            val handshakeOk = alive
            val hasTraffic = alive
            val healthOk = alive
            
            if (handshakeOk && (hasTraffic || healthOk)) {
                Log.i(TAG, "Evidence: ${provider.name} alive (handshake=$handshakeOk, traffic=$hasTraffic, health=$healthOk)")
                return true
            }
            
            val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "Waiting for ${provider.name} evidence: ${elapsed}ms / ${timeoutMs}ms")
        }
        
        Log.w(TAG, "Evidence timeout for ${provider.name}")
        return false
    }
    
    private fun hbHasBytes(): Boolean {
        return try {
            val inst = dev.hydra.hydra_client.HydraVpnService.instance ?: return false
            val f = java.io.File(inst.filesDir, "hb_stats.json")
            if (!f.exists()) return false
            val j = org.json.JSONObject(f.readText())
            (j.optLong("rx_bytes") + j.optLong("tx_bytes")) > 0L
        } catch (_: Exception) { false }
    }

    private fun wdttSocksAlive(): Boolean = try {
        java.net.Socket().connect(java.net.InetSocketAddress("127.0.0.1", 1082), 300)
        true
    } catch (_: Exception) { false }

    /**
     * Fast failover: переключение при деградации (UNSTABLE ×2 или stall).
     * Вызывается из ReconnectManager.
     */
    private val ncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var networkChangeJob: kotlinx.coroutines.Job? = null

    /** Смена сети (WiFi<->mobile): штрафы сети-предшественницы недействительны,
     *  активный туннель переподнимаем со свежим probe. */
    fun onNetworkChanged() {
        networkChangeJob?.cancel()
        networkChangeJob = ncScope.launch {
            kotlinx.coroutines.delay(3000) // дебаунс: даём сети устаканиться
            val ctx = context ?: return@launch
            val kid = keyId ?: return@launch
            ProtocolMemory.clearPenalties(ctx)
            if (!HydraVpnService.isRunning) {
                Log.i(TAG, "Network changed (idle): penalties cleared")
                return@launch
            }
            // roam-aware: сначала 6 сек наблюдаем rx — живой туннель не трогаем
            val rx0 = HydraVpnService.readTunStats(HydraVpnService.tunName).first
            kotlinx.coroutines.delay(6000)
            val rx1 = HydraVpnService.readTunStats(HydraVpnService.tunName).first
            // 0.4.7: проверка провайдера (конец "Roam survived" на мёртвом туннеле)
            if (HydraVpnService.isRunning && activeProvider?.isRunning() == true && rx1 > rx0) {
                Log.i(TAG, "Roam survived: rx $rx0 -> $rx1 — tunnel kept")
                return@launch
            }
            Log.w(TAG, "Network changed and tunnel stalled (rx frozen at $rx0) — reconnect with fresh probe")
            activeProvider?.disconnect()
            activeProvider = null
            HealthMonitor.stop()
            kotlinx.coroutines.delay(1500)
            autoConnect(ctx, kid)
        }
    }

    suspend fun failover(currentProvider: TunnelProvider?): Boolean {
        val ctx = context ?: return false
        val kid = keyId ?: return false
        val actualCurrent = currentProvider ?: activeProvider // 0.4.5: защита от null
        
        Log.i(TAG, "Failover triggered, current=${actualCurrent?.name}")
        DevLog.e("SmartConnectManager", "Failover: current=${actualCurrent?.name}, memory=${ProtocolMemory.dumpState(ctx)}")
        actualCurrent?.let { ProtocolMemory.recordFailure(ctx, it.name) }
        actualCurrent?.disconnect()
        
        uiMessage("Соединение потеряно, переподключение...")
        delay(2000) // даём teardown завершиться
        
        // Перезапускаем autoConnect (он сам выберет следующего кандидата)
        val newProvider = autoConnect(ctx, kid)
        return newProvider != null
    }
    
    private fun uiMessage(m: String) {
        lastStatusMessage = m
        statusMessageCallback?.invoke(m)
    }
    
    private fun sanitizeKeyId(keyId: String): String {
        return keyId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    }
    
    fun disconnect() {
        Log.i(TAG, "Disconnecting active provider")
        activeProvider?.disconnect()
        activeProvider = null
        HealthMonitor.stop()
    }
}
