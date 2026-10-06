package dev.hydra.hydra_client.tunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tunnel.Tunnel
import android.content.Context
import tunnel.NetworkProbeResult

object EnvironmentProbe {
    private const val TAG = "EnvironmentProbe"
    
    /** true если мобильная сеть режет прямой интернет (белые списки оператора). */
    @Volatile
    var whiteListDetected: Boolean = false
        private set

    private fun isCellular(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun googleReachable(): Boolean = runCatching {
        java.net.Socket().connect(java.net.InetSocketAddress("google.com", 443), 3000)
        true
    }.getOrElse { false }

    suspend fun probe(context: Context, endpoint: String): NetworkProbeResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Probing network for endpoint: $endpoint")
                // 5 секунд в наносекундах (gomobile не транслирует Duration)
                whiteListDetected = isCellular(context) && !googleReachable()
                if (whiteListDetected) Log.w(TAG, "White-list network detected: cellular + google.com unreachable")
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
