package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tunnel.NetworkProbeResult

object SmartConnectManager {
    private const val TAG = "SmartConnectManager"
    
    private val providers = mutableListOf<TunnelProvider>()
    private var activeProvider: TunnelProvider? = null
    
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
    
    fun registerProvider(provider: TunnelProvider) {
        providers.add(provider)
        Log.i(TAG, "Registered provider: ${provider.name} (${provider.serverId})")
    }
    
    suspend fun autoConnect(context: Context, keyId: String): TunnelProvider? {
        Log.i(TAG, "Starting Smart Connect...")
        
        // Парсим keyfile и строим провайдеры динамически
        val keysDir = java.io.File(context.filesDir, "keys")
        val keyFile = java.io.File(keysDir, "${sanitizeKeyId(keyId)}.key")
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
                "awg" -> AWGTunnelProvider(context, peer.serverId, peer.label, peer)
                "aivpn" -> AIVPNTunnelProvider(context, peer.serverId, peer.label, peer)
                "wdtt" -> WDTTTunnelProvider(context, peer.serverId, peer.label, peer)
                else -> {
                    Log.w(TAG, "Unknown protocol: ${peer.protocol}")
                    continue
                }
            }
            registerProvider(provider)
        }
        
        // Пробегаем по всем endpoint'ам для скоринга
        val firstEndpoint = kf.peers.firstOrNull()?.endpoint ?: "31.77.202.131:51820"
        val probe = EnvironmentProbe.probe(context, firstEndpoint)
        lastProbe = probe
        lastLatencyMs = probe.latencyMs
        
        val whiteList = EnvironmentProbe.whiteListDetected
        val hashCount = dev.hydra.hydra_client.SettingsStore.getHashList(context).size
        val preferred = dev.hydra.hydra_client.SettingsStore.getPreferredProtocol(context)

        val scored = providers.map { p ->
            var sc = p.score(probe)
            if (whiteList) {
                sc = when (p.name) {
                    "WDTT" -> sc + 50          // обход белых списков через VK TURN
                    else -> 0                  // AIVPN/AWG в белых списках не живут
                }
            }
            p to sc
        }.sortedByDescending { it.second }

        // Белые списки + выбран WDTT, но хешей нет — просим пользователя добавить
        needVkHashes = whiteList && hashCount == 0 &&
            scored.firstOrNull()?.first?.name == "WDTT"
        if (needVkHashes) {
            Log.e(TAG, "White-list network but no VK hashes configured — ask user")
            return null
        }
        
        Log.i(TAG, "Providers scored: ${scored.map { "${it.first.name}=${it.second}" }}, preferred=$preferred")
        
        // Если пользователь выбрал конкретный протокол — пробуем его первым
        var best: Pair<TunnelProvider, Int>? = null
        if (preferred != "auto") {
            val pref = scored.find { it.first.name.equals(preferred, ignoreCase = true) }
            if (pref != null && pref.second > 0) {
                best = pref
                Log.i(TAG, "Using preferred protocol: ${pref.first.name}")
            } else if (pref != null) {
                Log.w(TAG, "Preferred ${pref.first.name} scored 0, falling back to best available")
            }
        }
        if (best == null) {
            best = scored.firstOrNull { it.second > 0 }
        }
        if (best == null) {
            Log.e(TAG, "No viable providers (all scored 0)")
            return null
        }
        
        Log.i(TAG, "Connecting to ${best.first.name} (score=${best.second})")
        best.first.connect(keyId)
        activeProvider = best.first
        
        HealthMonitor.statusListener = { st -> ReconnectManager.onHealth(st) }
        HealthMonitor.start()
        
        return best.first
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
