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
        
        val endpoint = "31.77.202.131:51820"
        val probe = EnvironmentProbe.probe(endpoint)
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
        
        HealthMonitor.start()
        
        return best.first
    }
    
    fun disconnect() {
        activeProvider?.disconnect()
        activeProvider = null
        HealthMonitor.stop()
    }
}
