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
    
    @Volatile
    var lastLatencyMs: Long = 0
        private set
    
    @Volatile
    var lastProbe: NetworkProbeResult? = null
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
        
        val kf = keyfile.Keyfile.parse(keyFile.absolutePath)
        
        // Очищаем старые провайдеры
        providers.clear()
        
        // Регистрируем провайдер для каждого peer
        for (peer in kf.peers) {
            val provider = when (peer.protocol) {
                "awg" -> AWGTunnelProvider(context, peer.serverId, peer.label)
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
        val probe = EnvironmentProbe.probe(firstEndpoint)
        lastProbe = probe
        lastLatencyMs = probe.latencyMs
        
        val scored = providers.map { it to it.score(probe) }
            .sortedByDescending { it.second }
        
        Log.i(TAG, "Providers scored: ${scored.map { "${it.first.name}=${it.second}" }}")
        
        val best = scored.firstOrNull { it.second > 0 }
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
