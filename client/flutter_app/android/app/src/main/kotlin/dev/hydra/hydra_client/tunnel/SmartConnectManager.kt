package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import tunnel.NetworkProbeResult

object SmartConnectManager {
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
    
    suspend fun autoConnect(ctx: Context, kid: String): TunnelProvider? {
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
                "WDTT" -> 1
                "AIVPN" -> 2
                else -> 3
            } })

        // Белые списки + выбран WDTT, но хешей нет — просим пользователя добавить
        needVkHashes = whiteList && hashCount == 0 &&
            scored.firstOrNull()?.first?.name == "WDTT"
        if (needVkHashes) {
            Log.e(TAG, "White-list network but no VK hashes configured — ask user")
            return null
        }
        
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
        
        if (candidates.isEmpty()) {
            Log.e(TAG, "No viable candidates (all scored 0 or penalized)")
            return null
        }
        
        for ((provider, score) in candidates) {
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
            "AmneziaWG" -> 15_000L
            "AIVPN" -> 10_000L
            "WDTT" -> 12_000L
            else -> 15_000L
        }
        
        val startTime = android.os.SystemClock.elapsedRealtime()
        provider.connect(kid)
        
        // Poll каждые 2 сек: проверяем handshake + traffic
        while (android.os.SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            delay(2000)
            
            // Признак жизни: handshake complete + ненулевой трафик или HEALTHY
            val handshakeOk = dev.hydra.hydra_client.HydraVpnService.isHandshakeComplete
            val stats = dev.hydra.hydra_client.HydraVpnService.readTunStats(null)
            val hasTraffic = stats.first > 0 || stats.second > 0
            val healthOk = HealthMonitor.lastStatus == HealthMonitor.HealthStatus.HEALTHY
            
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
    
    /**
     * Fast failover: переключение при деградации (UNSTABLE ×2 или stall).
     * Вызывается из ReconnectManager.
     */
    suspend fun failover(currentProvider: TunnelProvider?): Boolean {
        val ctx = context ?: return false
        val kid = keyId ?: return false
        
        Log.i(TAG, "Failover triggered, current=${currentProvider?.name}")
        currentProvider?.let { ProtocolMemory.recordFailure(ctx, it.name) }
        currentProvider?.disconnect()
        
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
