package dev.hydra.hydra_client.tunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tunnel.Tunnel
import tunnel.NetworkProbeResult

object EnvironmentProbe {
    private const val TAG = "EnvironmentProbe"
    
    suspend fun probe(endpoint: String): NetworkProbeResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Probing network for endpoint: $endpoint")
                // 5 секунд в наносекундах (gomobile не транслирует Duration)
                val result = Tunnel.probeNetwork(endpoint, 5_000_000_000L)
                Log.d(TAG, "Probe: UDP=${result.udpOk}, TCP=${result.tcpOk}, VK=${result.vkApiOk}, DPI=${result.dpiDetected}, latency=${result.latencyMs}ms")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Probe failed", e)
                NetworkProbeResult()
            }
        }
    }
}
